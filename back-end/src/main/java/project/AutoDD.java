package project;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import main.DbConnector;

/** Created by wenbo on 3/31/19. */
public class AutoDD {

    private String query, db, rawTable;
    private String xCol, yCol, zCol;
    private int bboxW, bboxH;
    private int topk;
    private String clusterMode, zOrder;
    private ArrayList<String> columnNames, queriedColumnNames = null, columnTypes = null;
    private ArrayList<String> aggDimensionFields, aggMeasureFields;
    private int numLevels, topLevelWidth, topLevelHeight;
    private double overlap;
    private double zoomFactor;
    private int xColId = -1, yColId = -1;
    private double loX = Double.NaN, loY, hiX, hiY;
    private String mergeClusterAggs,
            getCitusSpatialHashKeyBody,
            singleNodeClusteringBody,
            mergeClustersAlongSplitsBody;

    public String getQuery() {
        return query;
    }

    public String getDb() {
        return db;
    }

    public String getxCol() {
        return xCol;
    }

    public String getyCol() {
        return yCol;
    }

    public int getBboxW() {
        return bboxW;
    }

    public int getBboxH() {
        return bboxH;
    }

    public int getTopk() {
        return topk;
    }

    public String getRawTable() {
        return rawTable;
    }

    public String getzCol() {
        return zCol;
    }

    public String getzOrder() {
        return zOrder;
    }

    public String getClusterMode() {
        return clusterMode;
    }

    public double getOverlap() {
        return overlap;
    }

    public int getXColId() {

        if (xColId < 0) {
            ArrayList<String> colNames = getColumnNames();
            for (int i = 0; i < colNames.size(); i++) if (colNames.get(i).equals(xCol)) xColId = i;
        }
        return xColId;
    }

    public int getYColId() {

        if (yColId < 0) {
            ArrayList<String> colNames = getColumnNames();
            for (int i = 0; i < colNames.size(); i++) if (colNames.get(i).equals(yCol)) yColId = i;
        }
        return yColId;
    }

    public ArrayList<String> getColumnNames() {

        // if it is specified already, return
        if (columnNames.size() > 0) return columnNames;

        // otherwise fetch the schema from DB
        if (queriedColumnNames == null)
            try {
                queriedColumnNames = new ArrayList<>();
                columnTypes = new ArrayList<>();
                Statement rawDBStmt = DbConnector.getStmtByDbName(getDb(), true);
                ResultSet rs =
                        DbConnector.getQueryResultIterator(
                                rawDBStmt, "SELECT * FROM " + rawTable + " limit 1;");
                int colCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    String curName = rs.getMetaData().getColumnName(i);
                    if (curName.equals("cx")
                            || curName.equals("cy")
                            || curName.equals("hash_key")
                            || curName.equals("centroid")) continue;
                    queriedColumnNames.add(curName);
                    columnTypes.add(rs.getMetaData().getColumnTypeName(i));
                }
                rs.close();
                rawDBStmt.close();
                DbConnector.closeConnection(getDb());
            } catch (Exception e) {
                e.printStackTrace();
            }
        return queriedColumnNames;
    }

    public ArrayList<String> getColumnTypes() {
        if (columnTypes != null) return columnTypes;
        getColumnNames();
        return columnTypes;
    }

    public ArrayList<String> getAggDimensionFields() {
        return aggDimensionFields;
    }

    public ArrayList<String> getAggMeasureFields() {
        return aggMeasureFields;
    }

    public int getNumLevels() {
        return numLevels;
    }

    public int getTopLevelWidth() {
        return topLevelWidth;
    }

    public int getTopLevelHeight() {
        return topLevelHeight;
    }

    public double getZoomFactor() {
        return zoomFactor;
    }

    public double getLoX() {
        return loX;
    }

    public double getLoY() {
        return loY;
    }

    public double getHiX() {
        return hiX;
    }

    public double getHiY() {
        return hiY;
    }

    public String getMergeClusterAggs() {
        return mergeClusterAggs;
    }

    public String getGetCitusSpatialHashKeyBody() {
        return getCitusSpatialHashKeyBody;
    }

    public String getSingleNodeClusteringBody() {
        return singleNodeClusteringBody;
    }

    public String getMergeClustersAlongSplitsBody() {
        return mergeClustersAlongSplitsBody;
    }

    // get the canvas coordinate of a raw value
    public double getCanvasCoordinate(int level, double v, boolean isX) {

        setXYExtent();
        if (isX)
            return ((topLevelWidth - bboxW) * (v - loX) / (hiX - loX) + bboxW / 2.0)
                    * Math.pow(zoomFactor, level);
        else
            return ((topLevelHeight - bboxH) * (v - loY) / (hiY - loY) + bboxH / 2.0)
                    * Math.pow(zoomFactor, level);
    }

    public void setXYExtent() {
        // calculate range if have not
        if (Double.isNaN(loX)) {

            System.out.println("\n Calculating autoDD x & y ranges...\n");
            loX = loY = Double.MAX_VALUE;
            hiX = hiY = Double.MIN_VALUE;
            try {
                Statement rawDBStmt = DbConnector.getStmtByDbName(getDb(), true);
                ResultSet rs = DbConnector.getQueryResultIterator(rawDBStmt, getQuery());
                while (rs.next()) {
                    double cx = rs.getDouble(getXColId() + 1);
                    double cy = rs.getDouble(getYColId() + 1);
                    loX = Math.min(loX, cx);
                    hiX = Math.max(hiX, cx);
                    loY = Math.min(loY, cy);
                    hiY = Math.max(hiY, cy);
                }
                rawDBStmt.close();
                DbConnector.closeConnection(getDb());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public String toString() {
        return "AutoDD{"
                + "query='"
                + query
                + '\''
                + ", db='"
                + db
                + '\''
                + ", xCol='"
                + xCol
                + '\''
                + ", yCol='"
                + yCol
                + '\''
                + ", bboxW="
                + bboxW
                + ", bboxH="
                + bboxH
                + '\''
                + ", clusterMode='"
                + clusterMode
                + '\''
                + ", columnNames="
                + columnNames
                + ", numLevels="
                + numLevels
                + ", topLevelWidth="
                + topLevelWidth
                + ", topLevelHeight="
                + topLevelHeight
                + ", overlap="
                + overlap
                + ", zoomFactor="
                + zoomFactor
                + '}';
    }
}
