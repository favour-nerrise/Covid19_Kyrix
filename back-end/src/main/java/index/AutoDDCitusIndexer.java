package index;

import box.Box;
import index.util.KDTree;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import main.Config;
import main.DbConnector;
import main.Main;
import project.AutoDD;
import project.Canvas;

/** Created by wenbo on 11/1/19. */
public class AutoDDCitusIndexer extends BoundingBoxIndexer {

    private static AutoDDCitusIndexer instance = null;
    private final int L = 7;
    private final int objectNumLimit = 4000; // in a 1k by 1k region
    private final int virtualViewportSize = 1000;
    private final int numPartitions = 16;
    private final int binarySearchMaxLoop = 8;
    private final String aggKeyDelimiter = "__";
    private AutoDD autoDD;
    private Statement kyrixStmt;
    private KDTree root = null;
    private Queue<KDTree> q = null;
    private double theta = 1.0, bottomScale = 1e10;
    private double loX, loY, hiX, hiY;
    private String rawTable, sql, xCol, yCol;
    private String curAutoDDId; // autoddIndex + "_0"
    private int curAutoDDIndex, numLevels, numRawColumns;
    private int topLevelWidth, topLevelHeight, bboxW, bboxH;
    private long st, st1, st2, numRawRows;
    private ArrayList<Integer> citusHashKeys;
    private ArrayList<String> citusNodeIPs, citusNodePorts;
    private ArrayList<ArrayList<String>> citusShardIds;
    private ArrayList<String> columnNames, columnTypes, zoomLevelTables;

    // singleton pattern to ensure only one instance existed
    private AutoDDCitusIndexer() {}

    // thread-safe instance getter
    public static synchronized AutoDDCitusIndexer getInstance() {

        if (instance == null) instance = new AutoDDCitusIndexer();
        return instance;
    }

    @Override
    public void createMV(Canvas c, int layerId) throws Exception {

        // create MV for all autoDD layers at once
        curAutoDDId = c.getLayers().get(layerId).getAutoDDId();
        if (!curAutoDDId.substring(curAutoDDId.indexOf("_") + 1).equals("0")) return;

        // set some commonly accessed variables, such as autoDD, numLevels, numRawColumns, etc.
        setCommonVariables();

        // step 1: spatial partitioning
        performSpatialPartitioning();

        // step 2: bottom-up clustering
        bottomUpClustering();
    }

    private void performSpatialPartitioning() throws SQLException, ClassNotFoundException {
        // augment raw table with centroid_point, sp_hash_key and spatial index on centroid
        augmentRawTable();

        // create a KD tree using spatial queries
        buildKDTree();

        // create all bbox tables;
        createBBoxTables();

        // generating citus hash keys
        generateCitusHashKeys();

        // populating a fake bottom level table, using a UDF to generate hash keys
        populateFakeBottomLevelTable();
    }

    private void bottomUpClustering() throws SQLException, ClassNotFoundException {
        // create the PLV8 function for single-node clustering
        createSingleNodeClusteringUDF();

        // create the PLV8 function
        createMergeSplitUDF();

        // bottom up clustering
        for (int i = numLevels - 1; i > L; i--) {
            // running single node clustering in parallel
            runSingleNodeClusteringUDFInParallel(i);

            // get count along boundaries
            mergeClustersAlongSplits(root, i);
        }

        for (int i = L; i >= 0; i--) {
            // apply single node clustering on
            // all data from level i + 1
            runSingleNodeClusteringUDF(i);
        }
    }

    private void setCommonVariables() throws SQLException, ClassNotFoundException {
        System.out.println("Setting common variables...");

        // get current AutoDD object
        curAutoDDIndex = Integer.valueOf(curAutoDDId.substring(0, curAutoDDId.indexOf("_")));
        autoDD = Main.getProject().getAutoDDs().get(curAutoDDIndex);

        // number of levels
        numLevels = autoDD.getNumLevels();
        System.out.println("numLevels = " + numLevels);

        // raw fields
        st = System.nanoTime();
        columnNames = autoDD.getColumnNames();
        columnTypes = autoDD.getColumnTypes();
        numRawColumns = columnNames.size();
        System.out.println("numRawColumns = " + numRawColumns);
        System.out.println("Raw columns: ");
        for (int i = 0; i < numRawColumns; i++)
            System.out.print(columnNames.get(i) + " " + columnTypes.get(i) + " ");
        System.out.println();
        System.out.println(
                "Getting # of raw columns took: " + (System.nanoTime() - st) / 1e9 + "s.");

        // raw table
        rawTable = autoDD.getRawTable();
        System.out.println("rawTable = " + rawTable);

        // calculate overlapping threshold
        theta =
                Math.max(
                        0.2,
                        Math.sqrt(
                                4
                                        * (virtualViewportSize + autoDD.getBboxW() * 2)
                                        * (virtualViewportSize + autoDD.getBboxH() * 2)
                                        / objectNumLimit
                                        / autoDD.getBboxH()
                                        / autoDD.getBboxW()));
        if (!autoDD.getOverlap()) theta = Math.max(theta, 1);
        System.out.println("theta = " + theta);

        // DB statement
        kyrixStmt = DbConnector.getStmtByDbName(Config.databaseName);

        // toplevel size
        topLevelWidth = autoDD.getTopLevelWidth();
        topLevelHeight = autoDD.getTopLevelHeight();
        System.out.println("topLevelWidth = " + topLevelWidth);
        System.out.println("topLevelHeight = " + topLevelHeight);

        // raw XY extent
        autoDD.setXYExtent();
        loX = autoDD.getLoX();
        loY = autoDD.getLoY();
        hiX = autoDD.getHiX();
        hiY = autoDD.getHiY();
        System.out.println("[loX, hiX] = [" + loX + ", " + hiX + "]");
        System.out.println("[loY, hiY] = [" + loY + ", " + hiY + "]");

        // xCol, yCol
        xCol = autoDD.getxCol();
        yCol = autoDD.getyCol();
        System.out.println("xCol = " + xCol);
        System.out.println("yCol = " + yCol);

        // bboxW, bboxH
        bboxW = autoDD.getBboxW();
        bboxH = autoDD.getBboxH();

        // zoom level table names
        zoomLevelTables = new ArrayList<>();
        for (int i = 0; i < numLevels; i++) zoomLevelTables.add("l" + i);
        zoomLevelTables.add("bottom_level");

        // citus hash key hashmap
        citusHashKeys = new ArrayList<>();

        // calculate numRaw columns
        sql = "SELECT count(*) FROM " + rawTable + ";";
        System.out.println(sql);
        st = System.nanoTime();
        numRawRows = Long.valueOf(DbConnector.getQueryResult(kyrixStmt, sql).get(0).get(0));
        System.out.println(
                "Running count(*) on "
                        + rawTable
                        + " took: "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");
        System.out.println("numRawRows = " + numRawRows);
    }

    private void augmentRawTable() throws SQLException {
        System.out.println("Augmenting raw data table...");

        // add columns
        sql = "ALTER TABLE " + rawTable + " ADD COLUMN IF NOT EXISTS cx real;";
        System.out.println(sql);
        kyrixStmt.executeUpdate(sql);
        sql = "ALTER TABLE " + rawTable + " ADD COLUMN IF NOT EXISTS cy real;";
        System.out.println(sql);
        kyrixStmt.executeUpdate(sql);
        sql = "ALTER TABLE " + rawTable + " ADD COLUMN IF NOT EXISTS centroid point;";
        System.out.println(sql);
        kyrixStmt.executeUpdate(sql);

        // update cx, cy
        sql =
                "UPDATE "
                        + rawTable
                        + " SET cx = ("
                        + (topLevelWidth - bboxW)
                        + " * ("
                        + xCol
                        + " - "
                        + loX
                        + ") / "
                        + (hiX - loX)
                        + " + "
                        + (bboxW / 2.0)
                        + ") * "
                        + bottomScale
                        + ", cy = ("
                        + (topLevelHeight - bboxH)
                        + " * ("
                        + yCol
                        + " - "
                        + loY
                        + ") / "
                        + (hiY - loY)
                        + " + "
                        + (bboxH / 2.0)
                        + ") * "
                        + bottomScale
                        + ";";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Populating cx, cy column on "
                        + rawTable
                        + " took: "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");

        // update centroid
        sql = "UPDATE " + rawTable + " SET centroid = point(cx, cy);";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Populating centroid column on "
                        + rawTable
                        + " took: "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");

        // create gist index on centroid. No CLUSTER here for now (the performance was ok w/o
        // CLUSTER)
        sql = "DROP INDEX IF EXISTS rawtable_centroid_gist;";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Drop existing index on centroid took: " + (System.nanoTime() - st) / 1e9 + "s.");

        sql = "CREATE INDEX rawtable_centroid_gist ON " + rawTable + " USING gist(centroid);";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Creating gist index on centroid took: " + (System.nanoTime() - st) / 1e9 + "s.");
    }

    private void buildKDTree() throws SQLException, ClassNotFoundException {
        System.out.println("Building KD tree...");

        // initialize root note
        double minx = bboxW / 2.0 * bottomScale;
        double miny = bboxH / 2.0 * bottomScale;
        double maxx = (topLevelWidth - bboxW / 2.0) * bottomScale;
        double maxy = (topLevelHeight - bboxH / 2.0) * bottomScale;
        System.out.println("KDTree root: " + minx + ", " + miny + ", " + maxx + ", " + maxy);
        root = new KDTree(minx, miny, maxx, maxy, KDTree.SplitDir.VERTICAL, numRawRows);

        // use a queue to expand the KD-tree
        st = System.nanoTime();
        q = new LinkedList<>();
        q.add(root);
        while (q.size() < numPartitions) {
            KDTree curNode = q.remove();
            // trying to split curNode into 2, using spatial queries
            double lo, hi, mid = 0;
            long halfCount = 0;
            if (curNode.splitDir.equals(KDTree.SplitDir.HORIZONTAL)) {
                lo = curNode.miny;
                hi = curNode.maxy;
            } else {
                lo = curNode.minx;
                hi = curNode.maxx;
            }
            // binar search
            st1 = System.nanoTime();
            for (int lp = 0; lp <= binarySearchMaxLoop; lp++) {
                mid = (lo + hi) / 2;
                // construct spatial query to fetch counts
                sql = "SELECT COUNT(*) FROM " + rawTable + " WHERE centroid <@ box('";
                if (curNode.splitDir.equals(KDTree.SplitDir.HORIZONTAL))
                    sql +=
                            curNode.minx
                                    + ", "
                                    + mid
                                    + ", "
                                    + curNode.maxx
                                    + ", "
                                    + curNode.miny
                                    + "');";
                else
                    sql +=
                            curNode.minx
                                    + ", "
                                    + curNode.maxy
                                    + ", "
                                    + mid
                                    + ", "
                                    + curNode.miny
                                    + "');";
                halfCount = Long.valueOf(DbConnector.getQueryResult(kyrixStmt, sql).get(0).get(0));
                if (halfCount < curNode.count - halfCount) lo = mid;
                else hi = mid;
            }
            curNode.splitPoint = mid;
            System.out.println("Current KD-tree node being split: " + curNode);
            System.out.println("Binary search took: " + (System.nanoTime() - st1) / 1e9 + "s.");

            // set split point && construct left child and right child
            if (curNode.splitDir.equals(KDTree.SplitDir.HORIZONTAL)) {
                curNode.lc =
                        new KDTree(
                                curNode.minx,
                                curNode.miny,
                                curNode.maxx,
                                mid,
                                KDTree.SplitDir.VERTICAL,
                                halfCount);
                curNode.rc =
                        new KDTree(
                                curNode.minx,
                                mid,
                                curNode.maxx,
                                curNode.maxy,
                                KDTree.SplitDir.VERTICAL,
                                curNode.count - halfCount);
            } else {
                curNode.lc =
                        new KDTree(
                                curNode.minx,
                                curNode.miny,
                                mid,
                                curNode.maxy,
                                KDTree.SplitDir.HORIZONTAL,
                                halfCount);
                curNode.rc =
                        new KDTree(
                                mid,
                                curNode.miny,
                                curNode.maxx,
                                curNode.maxy,
                                KDTree.SplitDir.HORIZONTAL,
                                curNode.count - halfCount);
            }

            // push left right children into the queue
            q.add(curNode.lc);
            q.add(curNode.rc);
        }

        System.out.println();
        System.out.println("Building KD-tree took: " + (System.nanoTime() - st) / 1e9 + "s.");

        // print out counts
        for (KDTree node : q) System.out.print(node.count + " ");
        System.out.println();
    }

    private void createBBoxTables() throws SQLException, ClassNotFoundException {
        System.out.println("Creating bbox tables for all levels...");

        // create tables
        for (int i = numLevels; i >= 0; i--) {
            String tableName = zoomLevelTables.get(i);

            // drop table if exists
            sql = "DROP TABLE IF EXISTS " + tableName;
            System.out.println(sql);
            kyrixStmt.executeUpdate(sql);

            // create table
            sql = "CREATE TABLE " + tableName + "(";
            for (int j = 0; j < numRawColumns; j++)
                sql += columnNames.get(j) + " " + columnTypes.get(j) + ", ";
            sql +=
                    "hash_key int, cluster_agg text, cx real, cy real, minx real, miny real, maxx real, maxy real, centroid point);";
            System.out.println(sql);
            kyrixStmt.executeUpdate(sql);

            // make it distributed
            if (i > L) {
                sql =
                        "SELECT create_distributed_table('"
                                + zoomLevelTables.get(i)
                                + "', 'hash_key'";
                if (i < numLevels)
                    sql += ", colocate_with => '" + zoomLevelTables.get(numLevels) + "'";
                sql += ");";
                System.out.println(sql);
                kyrixStmt.executeQuery(sql);
            }
        }
    }

    private void generateCitusHashKeys() throws SQLException, ClassNotFoundException {
        System.out.println("Generating citus hash keys...");

        // generate numPartition hash_keys so that none of them
        // are in the same shard for the fake bottom level table
        // (and therefore none of them are in the same shard for all tables
        // because tables are all colocated and use the same hash function)
        citusHashKeys = new ArrayList<>();
        citusShardIds = new ArrayList<>();
        for (int i = 0; i <= numLevels; i++) citusShardIds.add(new ArrayList<>());
        HashMap<String, Integer> bottomLevelShardIdMap = new HashMap<>();
        for (int key = 0; ; key++) {
            sql =
                    "SELECT get_shard_id_for_distribution_column('"
                            + zoomLevelTables.get(numLevels)
                            + "',"
                            + key
                            + ");";
            String shardId = DbConnector.getQueryResult(kyrixStmt, sql).get(0).get(0);
            if (bottomLevelShardIdMap.containsKey(shardId)) continue;
            bottomLevelShardIdMap.put(shardId, citusHashKeys.size());
            citusHashKeys.add(key);
            for (int i = L + 1; i <= numLevels; i++) {
                sql =
                        "SELECT get_shard_id_for_distribution_column('"
                                + zoomLevelTables.get(i)
                                + "',"
                                + key
                                + ");";
                shardId = DbConnector.getQueryResult(kyrixStmt, sql).get(0).get(0);
                citusShardIds.get(i).add(shardId);
            }
            if (citusHashKeys.size() >= numPartitions) break;
        }

        // check collision
        System.out.println();
        for (int i = L + 1; i <= numLevels; i++)
            for (int x = 0; x < numPartitions; x++)
                for (int y = x + 1; y < numPartitions; y++)
                    if (citusShardIds.get(i).get(x).equals(citusShardIds.get(i).get(y)))
                        System.out.println(
                                "Shard ID collision!!!!!! Level "
                                        + i
                                        + ":\n"
                                        + " partition "
                                        + x
                                        + " (key = "
                                        + citusHashKeys.get(x)
                                        + " ) and"
                                        + " partition "
                                        + y
                                        + " (key = "
                                        + citusHashKeys.get(y)
                                        + ")");

        // getting the node names and ports
        sql =
                "SELECT shardid, nodename, nodeport "
                        + "FROM pg_dist_node, pg_dist_placement "
                        + "WHERE pg_dist_node.groupid = pg_dist_placement.groupid;";
        System.out.println(sql);
        st = System.nanoTime();
        ArrayList<ArrayList<String>> res = DbConnector.getQueryResult(kyrixStmt, sql);
        citusNodeIPs = new ArrayList<>(Collections.nCopies(numPartitions, ""));
        citusNodePorts = new ArrayList<>(Collections.nCopies(numPartitions, ""));
        for (int i = 0; i < res.size(); i++) {
            String shardId = res.get(i).get(0);
            if (!bottomLevelShardIdMap.containsKey(shardId)) continue;
            int partitionId = bottomLevelShardIdMap.get(shardId);
            String nodeIP = res.get(i).get(1);
            String nodePort = res.get(i).get(2);
            citusNodeIPs.set(partitionId, nodeIP);
            citusNodePorts.set(partitionId, nodePort);
        }

        System.out.println("=============== now printing out partitions ===============");
        int ct = 0;
        for (KDTree o : q) {
            System.out.print("Hash key: " + citusHashKeys.get(ct) + ", ");
            System.out.print(
                    "Bottom level shard ID: " + citusShardIds.get(numLevels).get(ct) + ", ");
            System.out.print(
                    "ip = " + citusNodeIPs.get(ct) + ", port = " + citusNodePorts.get(ct) + ", ");
            System.out.print("minx = " + o.minx + ", miny = " + o.miny + ", ");
            System.out.print("maxx = " + o.maxx + ", maxy = " + o.maxy + ", ");
            System.out.println(" expected count = " + o.count);
            ct++;
        }
    }

    private void populateFakeBottomLevelTable() throws SQLException {
        System.out.println("Creating fake bottom level table...");

        // create a UDF generate spatial hash_id
        String funcSql =
                "CREATE OR REPLACE FUNCTION get_citus_spatial_hash_key("
                        + "cx real, cy real)"
                        + " returns int AS $$ ";
        String partitionStr = "[";
        for (KDTree o : q) {
            if (partitionStr.charAt(partitionStr.length() - 1) != '[') partitionStr += ",";
            partitionStr += "[" + o.minx + "," + o.miny + ", " + o.maxx + ", " + o.maxy + "]";
        }
        partitionStr += "]";
        String hashKeysStr = "[";
        for (int i = 0; i < citusHashKeys.size(); i++)
            hashKeysStr += (i > 0 ? "," : "") + citusHashKeys.get(i);
        hashKeysStr += "]";
        funcSql +=
                autoDD.getGetCitusSpatialHashKeyBody()
                        .replaceAll("REPLACE_ME_partitions", partitionStr)
                        .replaceAll("REPLACE_ME_hashkeys", hashKeysStr);
        funcSql += " $$ LANGUAGE plv8";
        sql = funcSql + " STABLE;";
        System.out.println("Creating get_citus_spatial_hash_key on master:\n" + sql);
        kyrixStmt.executeUpdate(sql);

        sql = "SELECT run_command_on_workers($cmd$ " + funcSql + " $cmd$);";
        System.out.println("CREATING get_citus_spatial_hash_key on workers:\n" + sql);
        kyrixStmt.executeQuery(sql);

        // big INSERT INTO bottom_level SELECT * FROM rawTable
        sql = "INSERT INTO " + zoomLevelTables.get(numLevels) + "(";
        for (int i = 0; i < numRawColumns; i++) sql += columnNames.get(i) + ", ";
        sql += "hash_key, cluster_agg, cx, cy) SELECT ";
        for (int i = 0; i < numRawColumns; i++) sql += columnNames.get(i) + ", ";
        sql +=
                "get_citus_spatial_hash_key(cx, cy), '{\"count(*)\":1}', cx, cy FROM "
                        + rawTable
                        + ";";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Populating "
                        + zoomLevelTables.get(numLevels)
                        + " took: "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");
    }

    private void createSingleNodeClusteringUDF() throws SQLException {
        System.out.println("Creating the PLV8 UDF for single node clustering algo...");
        String funcSql =
                "CREATE OR REPLACE FUNCTION single_node_clustering_algo("
                        + "shard text, autodd jsonb) returns setof jsonb AS $$ "
                        + autoDD.getSingleNodeClusteringBody()
                        + "$$ LANGUAGE plv8";
        sql = funcSql + " STABLE;";
        System.out.println("Creating single_node_clustering_algo on master:\n" + sql);
        kyrixStmt.executeUpdate(sql);

        sql = "SELECT run_command_on_workers($cmd$ " + funcSql + " $cmd$);";
        System.out.println("Creating single_node_clustering_algo on workers:\n" + sql);
        kyrixStmt.executeQuery(sql);
    }

    private void createMergeSplitUDF() throws SQLException {
        System.out.println("Creating the PLV8 UDF for merging clusters along splits...");

        sql =
                "CREATE OR REPLACE FUNCTION merge_clusters_along_splits("
                        + "clusters jsonb[], autodd jsonb) returns setof jsonb AS $$ "
                        + autoDD.getMergeClustersAlongSplitsBody()
                        + "$$ LANGUAGE plv8";
        sql = sql + " STABLE;";
        System.out.println("Creating merge_clusters_along_splits on master:\n" + sql);
        kyrixStmt.executeUpdate(sql);
    }

    private void runSingleNodeClusteringUDFInParallel(int i)
            throws SQLException, ClassNotFoundException {
        System.out.println(
                "Running single_node_clustering_algo across all shards for level" + i + "...");

        String curLevelTableName = zoomLevelTables.get(i);
        double zoomFactor = autoDD.getZoomFactor();
        if (i == numLevels - 1) zoomFactor = bottomScale / Math.pow(zoomFactor, numLevels - 1);

        // run udf with inserts
        sql =
                "SELECT single_node_clustering_algo('%1$s', '{"
                        + "\"zCol\":\""
                        + autoDD.getzCol()
                        + "\", \"zOrder\":\""
                        + autoDD.getzOrder()
                        + "\", \"zoomFactor\":"
                        + zoomFactor
                        + ", \"theta\":"
                        + theta
                        + ", \"bboxW\":"
                        + bboxW
                        + ", \"bboxH\":"
                        + bboxH
                        + ", \"fields\":[";
        // field names
        for (int j = 0; j < numRawColumns; j++) sql += "\"" + columnNames.get(j) + "\", ";
        sql +=
                "\"hash_key\", \"cluster_agg\", \"cx\", \"cy\","
                        + "\"minx\", \"miny\", \"maxx\", \"maxy\"]";
        // field types
        sql += ", \"types\":[";
        for (int j = 0; j < numRawColumns; j++) sql += "\"" + columnTypes.get(j) + "\", ";
        sql += "\"int\", \"text\", \"real\", \"real\"," + "\"real\", \"real\", \"real\", \"real\"]";
        // map of shard ids
        sql += ", \"tableMap\": {";
        for (int j = 0; j < numPartitions; j++)
            sql +=
                    (j > 0 ? ", " : "")
                            + "\""
                            + zoomLevelTables.get(i + 1)
                            + "_"
                            + citusShardIds.get(i + 1).get(j)
                            + "\" : \""
                            + curLevelTableName
                            + "_"
                            + citusShardIds.get(i).get(j)
                            + "\"";
        sql += "}}'::jsonb)";
        sql =
                "SELECT run_command_on_shards('"
                        + zoomLevelTables.get(i + 1)
                        + "', $$ "
                        + sql
                        + "$$);";
        System.out.println(sql);
        st = System.nanoTime();
        ArrayList<ArrayList<String>> res = DbConnector.getQueryResult(kyrixStmt, sql);
        System.out.println(
                "Running single node clustering in parallel took: "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");
        for (int j = 0; j < res.size(); j++) System.out.println(res.get(j).get(0));
        System.out.println();

        sql = "DROP INDEX IF EXISTS " + curLevelTableName + "_centroid_gist;";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Drop existing index on centroid took: " + (System.nanoTime() - st) / 1e9 + "s.");
        sql =
                "CREATE INDEX "
                        + curLevelTableName
                        + "_centroid_gist ON "
                        + curLevelTableName
                        + " USING gist(centroid);";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Creating gist index on centroid took: " + (System.nanoTime() - st) / 1e9 + "s.");
        System.out.println();
    }

    private void mergeClustersAlongSplits(KDTree o, int i)
            throws SQLException, ClassNotFoundException {
        if (o.lc == null || o.rc == null) return;

        // recursion, merge children first
        mergeClustersAlongSplits(o.lc, i);
        mergeClustersAlongSplits(o.rc, i);

        System.out.println();
        System.out.println("Current KD-tree: " + o);
        String tableName = zoomLevelTables.get(i);
        double zoomFactor = bottomScale / Math.pow(autoDD.getZoomFactor(), i);

        // get how many objects there are along the split
        String boxStr = "box('";
        if (o.splitDir == KDTree.SplitDir.VERTICAL)
            boxStr +=
                    (o.splitPoint / zoomFactor - bboxW * theta)
                            + ", "
                            + o.miny / zoomFactor
                            + ", "
                            + (o.splitPoint / zoomFactor + bboxW * theta)
                            + ", "
                            + o.maxy / zoomFactor;
        else
            boxStr +=
                    o.minx / zoomFactor
                            + ", "
                            + (o.splitPoint / zoomFactor - bboxH * theta)
                            + ", "
                            + o.maxx / zoomFactor
                            + ", "
                            + (o.splitPoint / zoomFactor + bboxH * theta);
        boxStr += "')";
        sql = "SELECT count(*) FROM " + tableName + " WHERE centroid <@ ";
        sql += boxStr + ";";
        System.out.println(sql);
        long count = Long.valueOf(DbConnector.getQueryResult(kyrixStmt, sql).get(0).get(0));
        System.out.println("# objects along this split: " + count);

        // if there is none, don't do anything
        if (count == 0) return;

        // get all objects into one table on the master
        sql = "DROP TABLE IF EXISTS merge_table;";
        System.out.println(sql);
        kyrixStmt.executeUpdate(sql);
        sql =
                "CREATE TABLE merge_table AS SELECT * FROM "
                        + tableName
                        + " WHERE centroid <@ "
                        + boxStr
                        + ";";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println("Creating merge_table took: " + (System.nanoTime() - st) / 1e9 + "s.");

        // delete from current zoom level all objects along the split
        sql = "DELETE FROM " + tableName + " WHERE centroid <@ " + boxStr + ";";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Deleting border clusters took : " + (System.nanoTime() - st) / 1e9 + "s.");

        // run udf to produce a new set of clusters, and insert directly back
        // using an INSERT INTO SELECT
        sql = "INSERT INTO " + tableName + " SELECT ";
        for (int j = 0; j < numRawColumns; j++)
            sql += "(v->>'" + columnNames.get(j) + "')::" + columnTypes.get(j) + ", ";
        sql +=
                "(v->>'hash_key')::int, (v->>'cluster_agg'),"
                        + " (v->>'cx')::real, (v->>'cy')::real,"
                        + " (v->>'minx')::real, (v->>'miny')::real,"
                        + " (v->>'maxx')::real, (v->>'maxy')::real,"
                        + " (v->>'centroid')::point ";
        sql +=
                "FROM (SELECT merge_clusters_along_splits(array_agg(to_jsonb(merge_table)), '{"
                        + "\"zCol\":\""
                        + autoDD.getzCol()
                        + "\", \"zOrder\":\""
                        + autoDD.getzOrder()
                        + "\", \"theta\":"
                        + theta
                        + ", \"bboxW\":"
                        + bboxW
                        + ", \"bboxH\":"
                        + bboxH
                        + ", \"splitDir\":"
                        + (o.splitDir == KDTree.SplitDir.VERTICAL
                                ? "\"vertical\""
                                : "\"horizontal\"")
                        + "}'::jsonb)v FROM merge_table) subquery";
        System.out.println(sql);
        st = System.nanoTime();
        System.out.println(kyrixStmt.executeUpdate(sql) + " new clusters inserted back.");
        System.out.println(
                "Running merge_clusters_along_splits took : "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");
    }

    private void runSingleNodeClusteringUDF(int i) throws SQLException {
        System.out.println(
                "Applying single_node_clustering_algo directly on master"
                        + " on clusters in level "
                        + (i + 1)
                        + " for level"
                        + i
                        + "...");

        double zoomFactor = autoDD.getZoomFactor();
        if (i == numLevels - 1) zoomFactor = bottomScale / Math.pow(zoomFactor, numLevels - 1);

        String curLevelTableName = zoomLevelTables.get(i);
        String lastLevelTableName = zoomLevelTables.get(i + 1);

        // then run udf and store results into the current level
        sql =
                "SELECT single_node_clustering_algo('"
                        + lastLevelTableName
                        + "', '{"
                        + "\"zCol\":\""
                        + autoDD.getzCol()
                        + "\", \"zOrder\":\""
                        + autoDD.getzOrder()
                        + "\", \"zoomFactor\":"
                        + zoomFactor
                        + ", \"theta\":"
                        + theta
                        + ", \"bboxW\":"
                        + bboxW
                        + ", \"bboxH\":"
                        + bboxH
                        + ", \"fields\":[";
        // field names
        for (int j = 0; j < numRawColumns; j++) sql += "\"" + columnNames.get(j) + "\", ";
        sql +=
                "\"hash_key\", \"cluster_agg\", \"cx\", \"cy\","
                        + "\"minx\", \"miny\", \"maxx\", \"maxy\"]";
        // field types
        sql += ", \"types\":[";
        for (int j = 0; j < numRawColumns; j++) sql += "\"" + columnTypes.get(j) + "\", ";
        sql += "\"int\", \"text\", \"real\", \"real\"," + "\"real\", \"real\", \"real\", \"real\"]";
        // map of shard ids
        sql += ", \"tableMap\": {\"" + lastLevelTableName + "\" : \"" + curLevelTableName + "\"";
        sql += "}}'::jsonb)";

        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeQuery(sql);
        System.out.println(
                "Running single node clustering on Citus master took: "
                        + (System.nanoTime() - st) / 1e9
                        + "s.");
        System.out.println();

        sql = "DROP INDEX IF EXISTS " + curLevelTableName + "_centroid_gist;";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Drop existing index on centroid took: " + (System.nanoTime() - st) / 1e9 + "s.");
        sql =
                "CREATE INDEX "
                        + curLevelTableName
                        + "_centroid_gist ON "
                        + curLevelTableName
                        + " USING gist(centroid);";
        System.out.println(sql);
        st = System.nanoTime();
        kyrixStmt.executeUpdate(sql);
        System.out.println(
                "Creating gist index on centroid took: " + (System.nanoTime() - st) / 1e9 + "s.");
        System.out.println();
    }

    @Override
    public ArrayList<ArrayList<String>> getDataFromRegion(
            Canvas c, int layerId, String regionWKT, String predicate, Box newBox, Box oldBox)
            throws Exception {
        return null;
    }

    @Override
    public ArrayList<ArrayList<String>> getDataFromTile(
            Canvas c, int layerId, int minx, int miny, String predicate) throws Exception {
        return null;
    }
}
