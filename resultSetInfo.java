/*-------------------------------------------------------------------------
 *
 *                foreign-data wrapper for JDBC
 *
 * Portions Copyright (c) 2021, TOSHIBA CORPORATION
 *
 * This software is released under the PostgreSQL Licence
 *
 * IDENTIFICATION
 *                jdbc_fdw/resultSetInfo.java
 *
 *-------------------------------------------------------------------------
 */
import java.sql.*;
import java.util.*;

public class resultSetInfo {
  private ResultSet resultSet;
  private Integer numberOfColumns;
  private int numberOfAffectedRows;
  private PreparedStatement pstmt;
  private List<Map.Entry<String, String>> columnInfo;

  public resultSetInfo(
      ResultSet fieldResultSet,
      List<Map.Entry<String, String>>  columnInfo
      ) {
    this.resultSet = fieldResultSet;
    if (columnInfo == null) throw new IllegalArgumentException("ColumnInfo cannot be null");
    if (columnInfo.size() == 0) throw new IllegalArgumentException("ColumnInfo cannot be empty");
    this.columnInfo = columnInfo;
    this.numberOfColumns = columnInfo.size();
  }
  public resultSetInfo(
      ResultSet fieldResultSet,
      Integer fieldNumberOfColumns
      ) {
    this.resultSet = fieldResultSet;
    this.numberOfColumns = fieldNumberOfColumns;
    this.columnInfo = null;
  }

  public resultSetInfo(
      int fieldNumberOfAffectedRows,
      PreparedStatement fieldPstmt
      ) {
    this.numberOfAffectedRows = fieldNumberOfAffectedRows;
    this.pstmt = fieldPstmt;
  }

  public void setPstmt(PreparedStatement fieldPstmt) {
    this.pstmt = fieldPstmt;
  }

  public void setNumberOfAffectedRows(int fieldNumberOfAffectedRows) {
    this.numberOfAffectedRows = fieldNumberOfAffectedRows;
  }

  public ResultSet getResultSet() {
    return resultSet;
  }

  public Integer getNumberOfColumns() {
    return numberOfColumns;
  }

  public int getNumberOfAffectedRows() {
    return numberOfAffectedRows;
  }

  public PreparedStatement getPstmt() {
    return pstmt;
  }
  public List<Map.Entry<String, String>> getColumnInfo() {
    return columnInfo;
  }
}
