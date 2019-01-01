package index;

import jdk.nashorn.api.scripting.NashornScriptEngine;
import main.Config;
import main.DbConnector;
import main.Main;
import project.Canvas;
import project.Layer;
import project.Transform;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;

/**
 * Created by wenbo on 12/31/18.
 */
public class MysqlSpatialIndexer extends Indexer {

    private static MysqlSpatialIndexer instance = null;

    // singleton pattern to ensure only one instance existed
    private MysqlSpatialIndexer() {}

    // thread-safe instance getter
    public static synchronized MysqlSpatialIndexer getInstance() {

        if (instance == null)
            instance = new MysqlSpatialIndexer();
        return instance;
    }

    @Override
    public void createMV(Canvas c, int layerId) throws Exception {

        // TODO: switch to prepared statements
        Statement bboxStmt, tileStmt;
        bboxStmt = DbConnector.getStmtByDbName(Config.databaseName);
        tileStmt = DbConnector.getStmtByDbName(Config.databaseName);

        Layer l = c.getLayers().get(layerId);
        Transform trans = l.getTransform();

        // step 0: create tables for storing bboxes and tiles
        String bboxTableName = "bbox_" + Main.getProject().getName() + "_" + c.getId() + "layer" + layerId;

        // drop table if exists
        String sql = "drop table if exists " + bboxTableName + ";";
        bboxStmt.executeUpdate(sql);

        // create the bbox table
        sql = "create table " + bboxTableName + " (";
        for (int i = 0; i < trans.getColumnNames().size(); i ++)
            sql += trans.getColumnNames().get(i) + " mediumtext, ";
        sql += "cx double precision, cy double precision, minx double precision, miny double precision, " +
                "maxx double precision, maxy double precision, geom polygon not null, spatial index (geom));";
        bboxStmt.executeUpdate(sql);

        // if this is an empty layer, continue
        if (trans.getDb().equals(""))
            return ;

        // step 1: set up nashorn environment, prepared statement, column name to id mapping
        NashornScriptEngine engine = null;
        if (! trans.getTransformFunc().equals(""))
            engine = setupNashorn(trans.getTransformFunc());

        // step 2: looping through query results
        // TODO: distinguish between separable and non-separable cases
        ResultSet rs = DbConnector.getQueryResultIterator(trans.getDb(), trans.getQuery());
        int numColumn = rs.getMetaData().getColumnCount();
        int rowCount = 0, mappingCount = 0;
        StringBuilder bboxInsSqlBuilder = new StringBuilder("insert into " + bboxTableName + " values");
        while (rs.next()) {

            // count log
            rowCount ++;
            if (rowCount % 1000000 == 0)
                System.out.println(rowCount);

            //get raw row
            ArrayList<String> curRawRow = new ArrayList<>();
            for (int i = 1; i <= numColumn; i ++)
                curRawRow.add(rs.getString(i));

            // step 3: run transform function on this tuple
            ArrayList<String> transformedRow;
            if (! trans.getTransformFunc().equals(""))
                transformedRow = getTransformedRow(c, curRawRow, engine);
            else
                transformedRow = curRawRow;

            // step 4: get bounding boxes coordinates
            ArrayList<Double> curBbox = Indexer.getBboxCoordinates(c, l, transformedRow);

            // insert into bbox table
            if (bboxInsSqlBuilder.charAt(bboxInsSqlBuilder.length() - 1) == ')')
                bboxInsSqlBuilder.append(",(");
            else
                bboxInsSqlBuilder.append(" (");
            for (int i = 0; i < transformedRow.size(); i ++)
                bboxInsSqlBuilder.append("'" + transformedRow.get(i).replaceAll("\'", "\\\\'") + "', ");
            for (int i = 0; i < 6; i ++)
                bboxInsSqlBuilder.append(String.valueOf(curBbox.get(i)) + ", ");

            double minx, miny, maxx, maxy;
            minx = curBbox.get(2);
            miny = curBbox.get(3);
            maxx = curBbox.get(4);
            maxy = curBbox.get(5);
            bboxInsSqlBuilder.append("ST_GeomFromText('");
            bboxInsSqlBuilder.append(getPolygonText(minx, miny, maxx, maxy));
            bboxInsSqlBuilder.append("'))");

            if (rowCount % Config.bboxBatchSize == 0) {
                bboxInsSqlBuilder.append(";");
                bboxStmt.executeUpdate(bboxInsSqlBuilder.toString());
                DbConnector.commitConnection(Config.databaseName);
                bboxInsSqlBuilder = new StringBuilder("insert into " + bboxTableName + " values");
            }
        }
        rs.close();
        DbConnector.closeConnection(trans.getDb());

        // insert tail stuff
        if (rowCount % Config.bboxBatchSize != 0) {
            bboxInsSqlBuilder.append(";");
            bboxStmt.executeUpdate(bboxInsSqlBuilder.toString());
            DbConnector.commitConnection(Config.databaseName);
        }

        // close db connections
        bboxStmt.close();
        tileStmt.close();
    }

    @Override
    public ArrayList<ArrayList<String>> getDataFromRegion(Canvas c, int layerId, String regionWKT, String predicate) throws Exception {

        // metadatabase statement
        Statement stmt = DbConnector.getStmtByDbName(Config.databaseName);

        // get column list string
        String colListStr = c.getLayers().get(layerId).getTransform().getColStr("");

        // construct range query
        String sql = "select " + colListStr + " from bbox_" + Main.getProject().getName() + "_"
                + c.getId() + "layer" + layerId + " where MBRIntersects(GeomFromText";
        sql += "('" + regionWKT + "'), geom)";
        if (predicate.length() > 0)
            sql += " and " + predicate + ";";
        System.out.println(sql);

        // return
        ArrayList<ArrayList<String>> ret = DbConnector.getQueryResult(stmt, sql);
        stmt.close();
        return ret;
    }

    @Override
    public ArrayList<ArrayList<String>> getDataFromTile(Canvas c, int layerId, int minx, int miny, String predicate) throws Exception {

        // get db connector
        Statement stmt = DbConnector.getStmtByDbName(Config.databaseName);

        // get column list string
        String colListStr = c.getLayers().get(layerId).getTransform().getColStr("");

        // construct range query
        String sql = "select " + colListStr + " from bbox_" + Main.getProject().getName() + "_"
                + c.getId() + "layer" + layerId + " where ";
        sql += "MBRIntersects(st_GeomFromText('Polygon((" + minx + " " + miny + "," + (minx + Config.tileW) + " " + miny;
        sql += "," + (minx + Config.tileW) + " " + (miny + Config.tileH) + "," + minx + " " + (miny + Config.tileH)
                + "," + minx + " " + miny + "))'),geom)";
        if (predicate.length() > 0)
            sql += " and " + predicate + ";";
        System.out.println(minx + " " + miny + " : " + sql);

        // return
        ArrayList<ArrayList<String>> ret = DbConnector.getQueryResult(stmt, sql);
        stmt.close();
        return ret;
    }
}