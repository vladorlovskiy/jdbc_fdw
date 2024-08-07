/*-------------------------------------------------------------------------
 *
 *                foreign-data wrapper for JDBC
 *
 * Copyright (c) 2012, PostgreSQL Global Development Group
 *
 * This software is released under the PostgreSQL Licence
 *
 * Author: Atri Sharma <atri.jiit@gmail.com>
 * Changes by: Heimir Sverrisson <heimir.sverrisson@gmail.com>, 2015-04-17
 *
 * Portions Copyright (c) 2021, TOSHIBA CORPORATION
 *
 * IDENTIFICATION
 *                jdbc_fdw/JDBCUtils.java
 *
 *-------------------------------------------------------------------------
 */

import java.io.*;
import java.net.URL;
import java.sql.*;
import java.time.LocalTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import net.snowflake.client.jdbc.SnowflakeConnection;
public class JDBCUtils {
  private Connection conn = null;
  private static JDBCDriverLoader jdbcDriverLoader;
  private StringWriter exceptionStringWriter;
  private PrintWriter exceptionPrintWriter;
  private int queryTimeoutValue;
  private Integer queryFetchSizeValue;
  private boolean withStackTrace;
  private Statement tmpStmt;
  private PreparedStatement tmpPstmt;
  private static ConcurrentHashMap<Integer, Connection> ConnectionHash = new ConcurrentHashMap<Integer, Connection>();
  private static int resultSetKey = 1;
  private static ConcurrentHashMap<Integer, resultSetInfo> resultSetInfoMap =
      new ConcurrentHashMap<Integer, resultSetInfo>();

  /*
   * createConnection
   *      Initiates the connection to the foreign database after setting
   *      up initial configuration.
   *      key - the serverid for the connection cache identifying
   *      Caller will pass in a six element array with the following elements:
   *          0 - Driver class name, 1 - JDBC URL, 2 - Username
   *          3 - Password, 4 - Query timeout in seconds, 5 - jarfile
   *
   */
  public void createConnection(int key, String[] options) throws Exception {
    DatabaseMetaData dbMetadata;
    Properties jdbcProperties;
    Class jdbcDriverClass = null;
    Driver jdbcDriver = null;
    String driverClassName = options[0];
    String url = options[1];
    String userName = options[2];
    String password = options[3];
    String qTimeoutOpt = options[4];
    String fileName = options[5];
    String qFetchSizeOpt = options[6];
    String qJdbcPropsOpt = options[7];
    String errorMessageWithStackTraceOpt = options[8];

    exceptionStringWriter = new StringWriter();
    exceptionPrintWriter = new PrintWriter(exceptionStringWriter);

    try {
      queryTimeoutValue = Integer.parseInt(qTimeoutOpt);
      queryFetchSizeValue = qFetchSizeOpt == null ? null : Integer.parseInt(qFetchSizeOpt);
      withStackTrace = Boolean.valueOf(errorMessageWithStackTraceOpt);

      File JarFile = new File(fileName);
      String jarfile_path = JarFile.toURI().toURL().toString();
      if (jdbcDriverLoader == null) {
        /* If jdbcDriverLoader is being created. */
        jdbcDriverLoader = new JDBCDriverLoader(new URL[] {JarFile.toURI().toURL()});
      } else if (jdbcDriverLoader.CheckIfClassIsLoaded(driverClassName) == null) {
        jdbcDriverLoader.addPath(jarfile_path);
      }
      jdbcDriverClass = jdbcDriverLoader.loadClass(driverClassName);
      jdbcDriver = (Driver) jdbcDriverClass.newInstance();
      jdbcProperties = new Properties();
      if (qJdbcPropsOpt != null) {
        jdbcProperties.loadFromXML(new ByteArrayInputStream(qJdbcPropsOpt.getBytes()));
      }
      if (userName != null) jdbcProperties.put("user", userName);
      if (password != null) jdbcProperties.put("password", password);
      /* get connection from cache */
      if (ConnectionHash.containsKey(key)) {
        conn = ConnectionHash.get(key);
      }
      if (conn == null) {
        conn = jdbcDriver.connect(url, jdbcProperties);
        ConnectionHash.put(key, conn);
      }
      dbMetadata = conn.getMetaData();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * createStatement
   *      Create a statement object based on the query
   */
  public void createStatement(String query) throws SQLException {
    /*
     *  Set the query select all columns for creating the same size of the result table
     *  because jvm can only return String[] - resultRow.
     *  Todo: return only necessary column.
     */
    try {
      checkConnExist();
      tmpStmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      if (queryTimeoutValue != 0) {
        tmpStmt.setQueryTimeout(queryTimeoutValue);
      }
      if (queryFetchSizeValue != null) {
        tmpStmt.setFetchSize(queryFetchSizeValue);
      }
      tmpStmt.executeQuery(query);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * createStatementID
   *      Create a statement object based on the query
   *      with a specific resultID and return back to the calling C function
   *      Returns:
   *          resultID on success
   */
  public int createStatementID(String query) throws Exception {
    ResultSet tmpResultSet;
    int tmpNumberOfColumns;
    ResultSetMetaData rSetMetadata;
    int tmpResultSetKey;
    try {
      checkConnExist();
      tmpStmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      if (queryTimeoutValue != 0) {
        tmpStmt.setQueryTimeout(queryTimeoutValue);
      }
      if (queryFetchSizeValue != null) {
        tmpStmt.setFetchSize(queryFetchSizeValue);
      }
      tmpResultSet = tmpStmt.executeQuery(query);
      rSetMetadata = tmpResultSet.getMetaData();
      tmpNumberOfColumns = rSetMetadata.getColumnCount();
      tmpResultSetKey = initResultSetKey();
      resultSetInfoMap.put(
          tmpResultSetKey,
          new resultSetInfo(tmpResultSet, tmpNumberOfColumns));
      return tmpResultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * clearResultSetID
   *      clear ResultSetID
   */
  public void clearResultSetID(int resultSetID) throws SQLException {
    try {
      checkConnExist();
      resultSetInfo info = resultSetInfoMap.remove(resultSetID);
      if (info != null) {
        ResultSet resultSet = info.getResultSet();
        if (resultSet != null && !resultSet.isClosed()) {
          Statement statement = resultSet.getStatement();
          try {
            resultSet.close();
          } catch (Throwable e) {
            if (withStackTrace) {
              e.printStackTrace(exceptionPrintWriter);
              throw new RuntimeException(exceptionStringWriter.toString(), e);
            }
          }
          if (statement != null && !statement.isClosed()) {
            try {
              statement.close();
            } catch (Throwable e) {
              if (withStackTrace) {
                e.printStackTrace(exceptionPrintWriter);
                throw new RuntimeException(exceptionStringWriter.toString(), e);
              }
            }
          }
        }
        PreparedStatement statement = info.getPstmt();
        if (statement != null && !statement.isClosed()) {
          try {
            statement.close();
          } catch (Throwable e) {
            if (withStackTrace) {
              e.printStackTrace(exceptionPrintWriter);
              throw new RuntimeException(exceptionStringWriter.toString(), e);
            }
          }
        }
      }
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * createPreparedStatement
   *      Create a PreparedStatement object based on the query
   *      with a specific resultID and return back to the calling C function
   *      Returns:
   *          resultID on success
   */
  public int createPreparedStatement(String query) throws Exception {
    try {
      checkConnExist();
      PreparedStatement tmpPstmt = (PreparedStatement) conn.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      if (queryTimeoutValue != 0) {
        tmpPstmt.setQueryTimeout(queryTimeoutValue);
      }
      if (queryFetchSizeValue != null) {
        tmpStmt.setFetchSize(queryFetchSizeValue);
      }
      int tmpResultSetKey = initResultSetKey();
      resultSetInfoMap.put(tmpResultSetKey, new resultSetInfo(0, tmpPstmt));
      return tmpResultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * execUpdatePreparedStatement
   *      Create a execUpdatePreparedStatement object based on the command
   */
  public void execUpdatePreparedStatement(int resultSetID) throws SQLException {
    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      int tmpNumberOfAffectedRows = tmpPstmt.executeUpdate();
      tmpPstmt.clearParameters();

      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
      resultSetInfoMap.get(resultSetID).setNumberOfAffectedRows(tmpNumberOfAffectedRows);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * execQueryPreparedStatement
   *      Create a execUpdatePreparedStatement object based on the command
   */
  public void execQueryPreparedStatement(int resultSetID) throws SQLException {
    try {
      checkConnExist();
      PreparedStatement pstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(pstmt);
      ResultSet resultSet = pstmt.executeQuery();
      ResultSetMetaData rsMetaData = resultSet.getMetaData();
      int numberOfColumns = rsMetaData.getColumnCount();
      resultSetInfo next = new resultSetInfo(resultSet, numberOfColumns);
      next.setNumberOfAffectedRows(pstmt.getUpdateCount());
      next.setPstmt(pstmt);
      resultSetInfoMap.put(resultSetID, next);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getNumberOfColumns
   *      Returns arrayOfNumberOfColumns[resultSetID]
   *      Returns:
   *          NumberOfColumns on success
   */
  public int getNumberOfColumns(int resultSetID) throws SQLException {
    try {
      return resultSetInfoMap.get(resultSetID).getNumberOfColumns();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getNumberOfAffectedRows
   *      Returns numberOfAffectedRows
   *      Returns:
   *          NumberOfAffectedRows on success
   */
  public int getNumberOfAffectedRows(int resultSetID) throws SQLException {
    try {
      return resultSetInfoMap.get(resultSetID).getNumberOfAffectedRows();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getResultSet
   *      Returns the result set that is returned from the foreign database
   *      after execution of the query to C code. One row is returned at a time
   *      as an Object array. For binary related types (BINARY, LONGVARBINARY, VARBINARY,
   *      BLOB), Object corresponds to byte array. For other types, Object corresponds to
   *      String. After last row null is returned.
   */
  public Object[] getResultSet(int resultSetID) throws SQLException {
    int i = 0;
    try {
      ResultSet tmpResultSet = resultSetInfoMap.get(resultSetID).getResultSet();
      int tmpNumberOfColumns = resultSetInfoMap.get(resultSetID).getNumberOfColumns();
      Object[] tmpArrayOfResultRow = new Object[tmpNumberOfColumns];
      ResultSetMetaData mtData = tmpResultSet.getMetaData();
      List<Map.Entry<String,String>> info = resultSetInfoMap.get(resultSetID).getColumnInfo();
      boolean silent = info != null;

      /* Row-by-row processing is done in jdbc_fdw.One row
       * at a time is returned to the C code. */
      if (tmpResultSet.next()) {
        for (i = 0; i < tmpNumberOfColumns; i++) {
          int columnType;
          try{
            columnType = mtData.getColumnType(i + 1);
          } catch (Throwable e){
            if (!silent) throw e;
            tmpArrayOfResultRow[i] = null;
            continue;
          }

          switch (columnType) {
            case Types.BINARY:
            case Types.LONGVARBINARY:
            case Types.VARBINARY:
            case Types.BLOB:
              /* Get byte array */
              tmpArrayOfResultRow[i] = tmpResultSet.getBytes(i + 1);
              break;
            case Types.TIMESTAMP:
              /*
               * Get the timestamp in UTC time zone by default
               * to avoid being affected by the remote server's time zone.
               */
              java.util.Calendar cal = Calendar.getInstance();
              cal.setTimeZone(TimeZone.getTimeZone("UTC"));
              Timestamp resTimestamp = tmpResultSet.getTimestamp(i + 1, cal);
              if (resTimestamp != null) {
                /* Timestamp is returned as text in ISO 8601 style */
                tmpArrayOfResultRow[i] = resTimestamp.toInstant().toString();
              } else {
                tmpArrayOfResultRow[i] = null;
              }
              break;
            default:
              /* Convert all columns to String */
              tmpArrayOfResultRow[i] = tmpResultSet.getString(i + 1);
          }
        }
        /* The current row in resultSet is returned
         * to the C code in a Java String array that
         * has the value of the fields of the current
         * row as it values. */
        return tmpArrayOfResultRow;
      } else {
        /*
         * All of resultSet's rows have been returned to the C code.
         * Close tmpResultSet's statement
         */
        if (tmpResultSet != null) {
          try {
            //TODO: Introduce a parameter for not tolerating the issue
            if (tmpResultSet.getStatement() != null) {
                tmpResultSet.getStatement().close();
            }
          } catch(Throwable e) {
            e.printStackTrace(exceptionPrintWriter);
          }
        }
        clearResultSetID(resultSetID);
        return null;
      }
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getColumnTypesByResultSetID
   *      Returns the column types
   */
  public String[] getColumnTypesByResultSetID(int resultSetID) throws SQLException {
    int i = 0;
    try {
      ResultSet tmpResultSet = resultSetInfoMap.get(resultSetID).getResultSet();
      ResultSetMetaData resultSetMetaData = tmpResultSet.getMetaData();
      int columnNumber = resultSetInfoMap.get(resultSetID).getNumberOfColumns();
      String[] tmpColumnTypesList = new String[columnNumber];
      List<Map.Entry<String,String>> info = resultSetInfoMap.get(resultSetID).getColumnInfo();

      for (i = 0; i < columnNumber; i++)
      {
        if (info != null && info.get(i) != null) {
          tmpColumnTypesList[i] = info.get(i).getValue();
          continue;
        }
        /* Column's index start from 1, so param of getColumnTypeName is (i + 1)  */
        int columnType = resultSetMetaData.getColumnType(i + 1);
        switch (columnType) {
          case Types.ARRAY:
            /* Array get from postgres server e.g interger[], bool[],... */
            //tmpColumnTypesList[i] = tmpResultSet.getString("TYPE_NAME");
            tmpColumnTypesList[i] = resultSetMetaData.getColumnTypeName(i + 1);
            break;
          case Types.BIGINT:
            tmpColumnTypesList[i] = "BIGINT";
            break;
          case Types.BINARY:
          case Types.BLOB:
          case Types.LONGVARBINARY:
          case Types.VARBINARY:
            tmpColumnTypesList[i] = "BYTEA";
            break;
          case Types.BIT:
          case Types.BOOLEAN:
            tmpColumnTypesList[i] = "BOOL";
            break;
          case Types.CHAR:
          case Types.LONGVARCHAR:
          case Types.VARCHAR:
          case Types.NVARCHAR:
          case Types.NCHAR:
            tmpColumnTypesList[i] = "TEXT";
            break;
          case Types.DATE:
            tmpColumnTypesList[i] = "DATE";
            break;
          case Types.DECIMAL:
          case Types.NUMERIC:
            tmpColumnTypesList[i] = "NUMERIC";
            break;
          case Types.DOUBLE:
            tmpColumnTypesList[i] = "FLOAT8";
            break;
          case Types.FLOAT:
          case Types.REAL:
            tmpColumnTypesList[i] = "FLOAT4";
            break;
          case Types.INTEGER:
            tmpColumnTypesList[i] = "INT4";
            break;
          case Types.SMALLINT:
          case Types.TINYINT:
            tmpColumnTypesList[i] = "INT2";
            break;
          case Types.TIME:
            tmpColumnTypesList[i] = "TIME";
            break;
          case Types.TIMESTAMP:
            /* timestamp need to mapping to timestamptz by default */
            tmpColumnTypesList[i] = "TIMESTAMPTZ";
            break;
          case Types.OTHER:
          {
            /* get type name from remote server */
            switch (resultSetMetaData.getColumnTypeName(i + 1)) {
              /*mapping type for gridDB*/
              case "BOOL_ARRAY":
                tmpColumnTypesList[i] = "BOOL[]";
                break;
              case "STRING_ARRAY":
                tmpColumnTypesList[i] = "TEXT[]";
                break;
              case "BYTE_ARRAY":
              case "SHORT_ARRAY":
                tmpColumnTypesList[i] = "INT2[]";
                break;
              case "INTEGER_ARRAY":
                tmpColumnTypesList[i] = "INTEGER[]";
                break;
              case "LONG_ARRAY":
                tmpColumnTypesList[i] = "BIGINT[]";
                break;
              case "FLOAT_ARRAY":
                tmpColumnTypesList[i] = "FLOAT4[]";
                break;
              case "DOUBLE_ARRAY":
                tmpColumnTypesList[i] = "FLOAT8[]";
                break;
              case "TIMESTAMP_ARRAY":
                /* Timestamp array from GridDB */
                tmpColumnTypesList[i] = "TIMESTAMPTZ[]";
                break;
              default:
                tmpColumnTypesList[i] =resultSetMetaData.getColumnTypeName(i + 1);
                break;
            }
            break;
          }
          default:
            tmpColumnTypesList[i] = resultSetMetaData.getColumnTypeName(i + 1);
            break;
        }
      }
      return tmpColumnTypesList;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getColumnNamesByResultSetID
   *      Returns the column name based on ResultSet
   */
  public String[] getColumnNamesByResultSetID(int resultSetID) throws SQLException {
    try {
      ResultSet tmpResultSet = resultSetInfoMap.get(resultSetID).getResultSet();
      ResultSetMetaData resultSetMetaData = tmpResultSet.getMetaData();
      int columnNumber = resultSetInfoMap.get(resultSetID).getNumberOfColumns();
      String[] tmpColumnNames = new String[columnNumber];
      List<Map.Entry<String,String>> info = resultSetInfoMap.get(resultSetID).getColumnInfo();

      for (int i = 0; i < columnNumber; i++)
      {
        if (info != null && info.get(i) != null) {
          tmpColumnNames[i] = info.get(i).getKey();
          continue;
        }
        tmpColumnNames[i] = resultSetMetaData.getColumnName(i + 1);
      }
      return tmpColumnNames;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getTables
   *   Returns the ResultSetId of calling conn.getMetaData().getTables(catalog, schemapattern, tablepattern, tabletype[])
   */
  public int getTables(String catalog, String schemaPattern, String tableNamePattern, String tableTypes) throws Throwable {
    // ResultSet columns are prefefined by JavaDoc. Some jdbc drivers do not implement the list fully
    // https://docs.oracle.com/en/java/javase/20/docs/api//java.sql/java/sql/DatabaseMetaData.html
    // The list of columns has to match with the result declaration of result of the jdbc_get_tables postgres function
    List<Map.Entry<String,String>> predefinedColumnInfo = List.of(
                Map.entry("TABLE_CAT", "text"),
                Map.entry("TABLE_SCHEM", "text"),
                Map.entry("TABLE_NAME", "text"),
                Map.entry("TABLE_TYPE", "text"),
                Map.entry("REMARKS", "text"),
                Map.entry("TYPE_CAT", "text"),
                Map.entry("TYPE_SCHEM", "text"),
                Map.entry("TYPE_NAME", "text"),
                Map.entry("SELF_REFERENCING_COL_NAME", "text"),
                Map.entry("REF_GENERATION", "text")
          );
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      int tmpResultSetKey = initResultSetKey();
      resultSetInfoMap.put(
          tmpResultSetKey,
          new resultSetInfo(
              md.getTables(catalog, schemaPattern, tableNamePattern, tableTypes == null ? null : tableTypes.split(",")),
              predefinedColumnInfo
          )
        );
      return tmpResultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

   /*
   * getTables
   *   Returns the ResultSetId of calling conn.getMetaData().getColumns(catalog, schemapattern, tablepattern, columnpattern)
   */
  public int getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws Throwable {
    // ResultSet columns are prefefined by JavaDoc. Some jdbc drivers do not implement the list fully
    // https://docs.oracle.com/en/java/javase/20/docs/api//java.sql/java/sql/DatabaseMetaData.html
    // The list of columns has to match with the result declaration of result of the jdbc_get_column postgres function
    List<Map.Entry<String,String>> predefinedColumnInfo = List.of(
            Map.entry("TABLE_CAT", "text"),
            Map.entry("TABLE_SCHEM", "text"),
            Map.entry("TABLE_NAME", "text"),
            Map.entry("COLUMN_NAME", "text"),
            Map.entry("DATA_TYPE", "int"),
            Map.entry("TYPE_NAME", "text"),
            Map.entry("COLUMN_SIZE", "int"),
            Map.entry("BUFFER_LENGTH", "int"),
            Map.entry("DECIMAL_DIGITS", "int"),
            Map.entry("NUM_PREC_RADIX", "int"),
            Map.entry("NULLABLE", "int"),
            Map.entry("REMARKS", "text"),
            Map.entry("COLUMN_DEF", "text"),
            Map.entry("SQL_DATA_TYPE", "int"),
            Map.entry("SQL_DATETIME_SUB", "int"),
            Map.entry("CHAR_OCTET_LENGTH", "int"),
            Map.entry("ORDINAL_POSITION", "int"),
            Map.entry("IS_NULLABLE", "text"),
            Map.entry("SCOPE_CATLOG", "text"),
            Map.entry("SCOPE_SCHEMA", "text"),
            Map.entry("SCOPE_TABLE", "text"),
            Map.entry("SOURCE_DATA_TYPE", "int"),
            Map.entry("IS_AUTOINCREMENT", "text"),
            Map.entry("IS_GENERATEDCOLUMN", "text")
      );
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      int tmpResultSetKey = initResultSetKey();
      resultSetInfoMap.put(
          tmpResultSetKey,
          new resultSetInfo(
              md.getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern),
              predefinedColumnInfo
            )
        );
      return tmpResultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }


  /*
   * getTableNames
   *      Returns the column name
   */
  public String[] getTableNames() throws SQLException {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      ResultSet tmpResultSet = md.getTables(null, null, "%", null);
      List<String> tmpTableNamesList = new ArrayList<String>();
      while (tmpResultSet.next()) {
        tmpTableNamesList.add(tmpResultSet.getString(3));
      }
      String[] tmpTableNames = new String[tmpTableNamesList.size()];
      for (int i = 0; i < tmpTableNamesList.size(); i++) {
        tmpTableNames[i] = tmpTableNamesList.get(i);
      }
      return tmpTableNames;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getColumnNames
   *      Returns the column name
   */
  public String[] getColumnNames(String tableName) throws SQLException {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      ResultSet tmpResultSet = md.getColumns(null, null, tableName, null);
      List<String> tmpColumnNamesList = new ArrayList<String>();
      while (tmpResultSet.next()) {
        tmpColumnNamesList.add(tmpResultSet.getString("COLUMN_NAME"));
      }
      String[] tmpColumnNames = new String[tmpColumnNamesList.size()];
      for (int i = 0; i < tmpColumnNamesList.size(); i++) {
        tmpColumnNames[i] = tmpColumnNamesList.get(i);
      }
      return tmpColumnNames;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getColumnTypes
   *      Returns the column name
   */
  public String[] getColumnTypes(String tableName) throws SQLException {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      ResultSet tmpResultSet = md.getColumns(null, null, tableName, null);
      List<String> tmpColumnTypesList = new ArrayList<String>();
      while (tmpResultSet.next()) {
        tmpColumnTypesList.add(tmpResultSet.getString("TYPE_NAME"));
      }
      String[] tmpColumnTypes = new String[tmpColumnTypesList.size()];
      for (int i = 0; i < tmpColumnTypesList.size(); i++) {
        switch (tmpColumnTypesList.get(i)) {
          case "BYTE":
          case "SHORT":
            tmpColumnTypes[i] = "SMALLINT";
            break;
          case "LONG":
            tmpColumnTypes[i] = "BIGINT";
            break;
          case "CHAR":
            tmpColumnTypes[i] = "CHAR (1)";
            break;
          case "STRING":
            tmpColumnTypes[i] = "TEXT";
            break;
          case "FLOAT":
            tmpColumnTypes[i] = "FLOAT4";
            break;
          case "DOUBLE":
            tmpColumnTypes[i] = "FLOAT8";
            break;
          case "BLOB":
            tmpColumnTypes[i] = "BYTEA";
            break;
          case "BOOL_ARRAY":
            tmpColumnTypes[i] = "BOOL[]";
            break;
          case "STRING_ARRAY":
            tmpColumnTypes[i] = "TEXT[]";
            break;
          case "BYTE_ARRAY":
          case "SHORT_ARRAY":
            tmpColumnTypes[i] = "SMALLINT[]";
            break;
          case "INTEGER_ARRAY":
            tmpColumnTypes[i] = "INTEGER[]";
            break;
          case "LONG_ARRAY":
            tmpColumnTypes[i] = "BIGINT[]";
            break;
          case "FLOAT_ARRAY":
            tmpColumnTypes[i] = "FLOAT4[]";
            break;
          case "DOUBLE_ARRAY":
            tmpColumnTypes[i] = "FLOAT8[]";
            break;
          case "TIMESTAMP_ARRAY":
            tmpColumnTypes[i] = "TIMESTAMP[]";
            break;
          default:
            tmpColumnTypes[i] = tmpColumnTypesList.get(i);
        }
      }
      return tmpColumnTypes;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getPrimaryKey
   *      Returns the column name
   */
  public String[] getPrimaryKey(String tableName) throws SQLException {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      ResultSet tmpResultSet = md.getPrimaryKeys(null, null, tableName);
      List<String> tmpPrimaryKeyList = new ArrayList<String>();
      while (tmpResultSet.next()) {
        tmpPrimaryKeyList.add(tmpResultSet.getString("COLUMN_NAME"));
      }
      String[] tmpPrimaryKey = new String[tmpPrimaryKeyList.size()];
      for (int i = 0; i < tmpPrimaryKeyList.size(); i++) {
        tmpPrimaryKey[i] = tmpPrimaryKeyList.get(i);
      }
      return tmpPrimaryKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * closeStatement
   *      Releases the resources used by statement. Keeps the connection
   *      open for another statement to be executed.
   */
  public void closeStatement() throws SQLException {
    try {
      resultSetInfoMap.clear();

      if (tmpStmt != null) {
        try{
          //TODO: Introduce a parameter for not tolerating the issue
          tmpStmt.close();
        } catch (Throwable e) {
          e.printStackTrace(exceptionPrintWriter);
        }
        tmpStmt = null;
      }
      if (tmpPstmt != null) {
        try {
          //TODO: Introduce a parameter for not tolerating the issue
          tmpPstmt.close();
        } catch (Throwable e) {
           e.printStackTrace(exceptionPrintWriter);
        }
        tmpPstmt = null;
      }
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * closeConnection
   *     Releases the resources used by connection.
   */
  public void closeConnection() throws SQLException {
    try {
      closeStatement();
      if (conn != null) {
        try {
          //TODO: Introduce a parameter for not tolerating the issue
          conn.close();
        } catch (Throwable e) {
          e.printStackTrace(exceptionPrintWriter);
        }
        conn = null;
      }
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * cancel
   *      Cancels the query and releases the resources in case query
   *      cancellation is requested by the user.
   */
  public void cancel() throws SQLException {
    try {
      closeStatement();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * checkConnExist
   *      Check the cennection exist or not.
   *      throw error message when the connection dosn't exist.
   */
  public void checkConnExist() throws IllegalArgumentException {
    if (conn == null) {
      throw new IllegalArgumentException(
          "Must create connection before creating a prepared statment");
    }
  }

  /*
   * checkPstmt
   *      Check the Prepared Statement exists or not.
   *      throw error message when the Prepared Statement doesn't exist.
   */
  public void checkPstmt(PreparedStatement pstmt) throws IllegalArgumentException {
    if (pstmt == null) {
      throw new IllegalArgumentException(
          "Must create a prior prepared statement before execute it");
    }
  }

  /*
   * bindNullPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindNullPreparedStatement(int attnum, int resultSetID) throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      if (tmpPstmt != null && tmpPstmt.getParameterMetaData() != null) {
        int sqlType = tmpPstmt.getParameterMetaData().getParameterType(attnum);
        tmpPstmt.setNull(attnum, sqlType);
      } else {
        tmpPstmt.setNull(attnum, Types.NULL);
      }
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindIntPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindIntPreparedStatement(int values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      tmpPstmt.setInt(attnum, values);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindLongPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindLongPreparedStatement(long values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      tmpPstmt.setLong(attnum, values);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindFloatPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindFloatPreparedStatement(float values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      tmpPstmt.setFloat(attnum, values);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindDoublePreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindDoublePreparedStatement(double values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      tmpPstmt.setDouble(attnum, values);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindBooleanPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindBooleanPreparedStatement(boolean values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      tmpPstmt.setBoolean(attnum, values);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindStringPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindStringPreparedStatement(String values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      tmpPstmt.setString(attnum, values);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindByteaPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindByteaPreparedStatement(byte[] dat, long length, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      InputStream targetStream = new ByteArrayInputStream(dat);
      tmpPstmt.setBinaryStream(attnum, targetStream, length);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindTimePreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindTimePreparedStatement(String values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      String pattern = "[HH:mm:ss][.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S][z][XXX][X]";
      LocalTime localTime = LocalTime.parse(values, DateTimeFormatter.ofPattern(pattern));
      tmpPstmt.setObject(attnum, localTime);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * bindTimeTZPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   *      set with localtime: might lost time-zone
   */
  public void bindTimeTZPreparedStatement(String values, int attnum, int resultSetID)
      throws SQLException {

    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);

      String pattern = "[HH:mm:ss][.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S][z][XXX][X]";
      LocalTime localTime = LocalTime.parse(values, DateTimeFormatter.ofPattern(pattern));
      tmpPstmt.setObject(attnum, localTime);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * Set timestamp to prepared statement
   * Use the UTC time zone as default to avoid being affected by the JVM time zone
   */
  private void setTimestamp(PreparedStatement preparedStatement, int attnum, Timestamp timestamp)
    throws SQLException {
      java.util.Calendar cal = Calendar.getInstance();
      cal.setTimeZone(TimeZone.getTimeZone("UTC"));
      try {
        /* Specify time zone (cal) if possible */
        preparedStatement.setTimestamp(attnum, timestamp, cal);
      } catch (SQLFeatureNotSupportedException e) {
        /* GridDB only, no calendar support in setTimestamp() */
        preparedStatement.setTimestamp(attnum, timestamp);
      } catch (Throwable e) {
        if (withStackTrace) {
          e.printStackTrace(exceptionPrintWriter);
          throw new RuntimeException(exceptionStringWriter.toString(), e);
        }
        throw e;
      }
    }

  /*
   * bindTimestampPreparedStatement
   *      Bind the value to the PreparedStatement object based on the query
   */
  public void bindTimestampPreparedStatement(long usec, int attnum, int resultSetID)
    throws SQLException {
    try {
      checkConnExist();
      PreparedStatement tmpPstmt = resultSetInfoMap.get(resultSetID).getPstmt();
      checkPstmt(tmpPstmt);
      Instant instant = Instant.EPOCH.plus(usec, ChronoUnit.MICROS);
      Timestamp timestamp = Timestamp.from(instant);
      setTimestamp(tmpPstmt, attnum, timestamp);
      resultSetInfoMap.get(resultSetID).setPstmt(tmpPstmt);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * Avoid race case.
   */
  synchronized public int initResultSetKey() throws Exception{
    try{
      int datum = resultSetKey;
      while (resultSetInfoMap.containsKey(resultSetKey)) {
        /* avoid giving minus key */
        if (resultSetKey == Integer.MAX_VALUE) {
          resultSetKey = 1;
        }
        resultSetKey++;
        /* resultSetKey full */
        if (resultSetKey == datum) {
          throw new SQLException("resultSetKey is full");
        }
      }
      return resultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * Get identifier quote char from remote server
   */
  public String getIdentifierQuoteString() throws SQLException{
    try{
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getIdentifierQuoteString();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }



  /*
   * getCatalogs
   *   Returns the ResultSetId of calling conn.getMetaData().getCatalogs()
   */
  public int getCatalogs() throws Throwable {
    // ResultSet columns are prefefined by JavaDoc. Some jdbc drivers do not implement the list fully
    // https://docs.oracle.com/en/java/javase/20/docs/api//java.sql/java/sql/DatabaseMetaData.html
    // The list of columns has to match with the result declaration of result of the jdbc_get_tables postgres function
    List<Map.Entry<String,String>> predefinedColumnInfo = List.of(
                Map.entry("TABLE_CAT", "text")
          );
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      int tmpResultSetKey = initResultSetKey();
      resultSetInfoMap.put(
          tmpResultSetKey,
          new resultSetInfo(
              md.getCatalogs(),
              predefinedColumnInfo
          )
        );
      return tmpResultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }


  /*
   * getSchemas
   *   Returns the ResultSetId of calling conn.getMetaData().getSchemas(catalog, schemapattern)
   */
  public int getSchemas(String catalog, String schemaPattern) throws Throwable {
    // ResultSet columns are prefefined by JavaDoc. Some jdbc drivers do not implement the list fully
    // https://docs.oracle.com/en/java/javase/20/docs/api//java.sql/java/sql/DatabaseMetaData.html
    // The list of columns has to match with the result declaration of result of the jdbc_get_tables postgres function
    List<Map.Entry<String,String>> predefinedColumnInfo = List.of(
                Map.entry("TABLE_SCHEM", "text"),
                Map.entry("TABLE_CATALOG ", "text")
          );
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      int tmpResultSetKey = initResultSetKey();
      resultSetInfoMap.put(
          tmpResultSetKey,
          new resultSetInfo(
              md.getSchemas(catalog, schemaPattern),
              predefinedColumnInfo
          )
        );
      return tmpResultSetKey;
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  public boolean getAutoCommit() throws Throwable {
    try {
      checkConnExist();
      return conn.getAutoCommit();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }
  public void setAutoCommit(boolean flag) throws Throwable {
    try {
      checkConnExist();
      conn.setAutoCommit(flag);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }






  /* DRIVER INFORMATION */
  /*
   * getDriverMajorVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDriverMajorVersion()
   */
  public int getDriverMajorVersion() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDriverMajorVersion();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getDriverMinorVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDriverMinorVersion()
   */
  public int getDriverMinorVersion() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDriverMinorVersion();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }
  /*
   * getDriverVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDriverVersion()
   */
  public String getDriverVersion() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDriverVersion();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getDriverVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDriverName()
   */
  public String getDriverName() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDriverName();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /* DATABASE INFORMATION */
  /*
   * getDatabaseMajorVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDatabaseMajorVersion()
   */
  public int getDatabaseMajorVersion() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDatabaseMajorVersion();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getDatabaseMinorVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDatabaseMinorVersion()
   */
  public int getDatabaseMinorVersion() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDatabaseMinorVersion();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getDatabaseProductVersion
   *   Returns the ResultSetId of calling conn.getMetaData().getDatabaseProductVersion()
   */
  public String getDatabaseProductVersion() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDatabaseProductVersion();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  /*
   * getDatabaseProductName
   *   Returns the ResultSetId of calling conn.getMetaData().getDatabaseProductName()
   */
  public String getDatabaseProductName() throws Throwable {
    try {
      checkConnExist();
      DatabaseMetaData md = conn.getMetaData();
      return md.getDatabaseProductName();
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  public int execUpdate(String sqlCommand) throws SQLException {
    try {
      checkConnExist();
      return conn.createStatement().executeUpdate(sqlCommand);
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

  public void snowflakeUploadToStage(String stageName, String destPrefix, String data, String fileName, boolean compress) throws Throwable {
    try {
      checkConnExist();
      try (InputStream idata = new ByteArrayInputStream(data.getBytes())) {
        conn.unwrap(SnowflakeConnection.class).uploadStream(stageName, destPrefix, idata, fileName, compress);
      }
    } catch (Throwable e) {
      if (withStackTrace) {
        e.printStackTrace(exceptionPrintWriter);
        throw new RuntimeException(exceptionStringWriter.toString(), e);
      }
      throw e;
    }
  }

}
