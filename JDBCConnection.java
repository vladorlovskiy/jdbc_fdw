/*-------------------------------------------------------------------------
 *
 *                foreign-data wrapper for JDBC
 *
 * Portions Copyright (c) 2023, TOSHIBA CORPORATION
 *
 * This software is released under the PostgreSQL Licence
 *
 * IDENTIFICATION
 *                jdbc_fdw/JDBCConnection.java
 *
 *-------------------------------------------------------------------------
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class JDBCConnection {
  private Connection conn = null;
  private boolean invalidate;
  private long server_hashvalue; // keep the uint32 val
  private long mapping_hashvalue; // keep the uint32 val
  
  private Integer queryTimeoutValue;
  private Integer queryFetchSizeValue;
  private boolean errorWithStackTrace;

  private static JDBCDriverLoader jdbcDriverLoader;

  /* JDBC connection hash map */
  private static ConcurrentHashMap<Integer, JDBCConnection> ConnectionHash = new ConcurrentHashMap<Integer, JDBCConnection>();

  public JDBCConnection(Connection conn, boolean invalidate, long server_hashvalue, long mapping_hashvalue, Integer queryTimeoutValue, Integer queryFetchSizeValue, boolean errorWithStackTrace) {
    this.conn = conn;
    this.invalidate = invalidate;
    this.server_hashvalue = server_hashvalue;
    this.mapping_hashvalue = mapping_hashvalue;
    this.queryTimeoutValue = queryTimeoutValue;
    this.queryFetchSizeValue = queryFetchSizeValue;
    this.errorWithStackTrace = errorWithStackTrace;
  }

  /* finalize all actived connection */
  public static void finalizeAllConns(long hashvalue) throws Exception {
    for (JDBCConnection Jconn : ConnectionHash.values()) {
      Jconn.invalidate = true;

      if (Jconn.conn != null) {
        Jconn.conn.close();
        Jconn.conn = null;
      }
    }
  }

  /* finalize connection have given server_hashvalue */
  public static void finalizeAllServerConns(long hashvalue) throws Exception {
    for (JDBCConnection Jconn : ConnectionHash.values()) {
      if (Jconn.server_hashvalue == hashvalue) {
        Jconn.invalidate = true;
        System.out.println("Finalizing " +  Jconn);

        if (Jconn.conn != null) {
          Jconn.conn.close();
          Jconn.conn = null;
        }
        break;
      }
    }
  }

    /* finalize connection have given mapping_hashvalue */
    public static void finalizeAllUserMapingConns(long hashvalue) throws Exception {
        for (JDBCConnection Jconn : ConnectionHash.values()) {
            if (Jconn.mapping_hashvalue == hashvalue) {
                Jconn.invalidate = true;
                System.out.println("Finalizing " +  Jconn);

                if (Jconn.conn != null) {
                    Jconn.conn.close();
                    Jconn.conn = null;
                }
                break;
            }
        }
    }

    /* get query timeout value */
    public int getQueryTimeout() {
        return queryTimeoutValue;
    }

    public Connection getConnection() {
        return this.conn;
    }

    public boolean errorWithStackTrace() {
        return this.errorWithStackTrace;
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        Statement tmpStmt = this.conn.createStatement(resultSetType, resultSetConcurrency);
        if (this.queryTimeoutValue != null) {
            tmpStmt.setQueryTimeout(this.queryTimeoutValue);
        }
        if (this.queryFetchSizeValue != null) {
            tmpStmt.setFetchSize(this.queryFetchSizeValue);
        }
        return tmpStmt;
    }

    public DatabaseMetaData getMetaData() throws SQLException {
        return this.conn.getMetaData();
    }

    public Statement createStatement() throws SQLException {
        Statement tmpStmt = this.conn.createStatement();
        if (this.queryTimeoutValue != null) {
            tmpStmt.setQueryTimeout(this.queryTimeoutValue);
        }
        if (this.queryFetchSizeValue != null) {
            tmpStmt.setFetchSize(this.queryFetchSizeValue);
        }
        return tmpStmt;
    }

    public PreparedStatement prepareStatement(String query, int resultSetType, int resultSetConcurrency) throws SQLException {
        PreparedStatement tmpPstmt = this.conn.prepareStatement(query, resultSetType, resultSetConcurrency);
        if (this.queryTimeoutValue != null) {
            tmpPstmt.setQueryTimeout(this.queryTimeoutValue);
        }
        if (this.queryFetchSizeValue != null) {
            tmpPstmt.setFetchSize(this.queryFetchSizeValue);
        }
        return tmpPstmt;
    }


    /* get jdbc connection, create new one if not cached before */
    public static JDBCConnection getConnection(int key, long server_hashvalue, long mapping_hashvalue, String[] options) throws Exception {
        if (ConnectionHash.containsKey(key)) {
            JDBCConnection Jconn = ConnectionHash.get(key);

            if (Jconn.invalidate == false) {
                // System.out.println("got connection " + Jconn.getConnection());
                return Jconn;
            }

        }

        return createConnection(key, server_hashvalue, mapping_hashvalue, options);
    }

    /* Make new connection */
    public static JDBCConnection createConnection(int key, long server_hashvalue, long mapping_hashvalue, String[] options) throws Exception {
        Properties jdbcProperties;
        Class<?> jdbcDriverClass = null;
        Driver jdbcDriver = null;
        String driverClassName = options[0];
        String url = options[1];
        String userName = options[2];
        String password = options[3];
        String qTimeoutString = options[4];
        String fileName = options[5];

        String qFetchSizeString = options[6];
        String qJdbcPropsString = options[7];
        String errorWithStackTraceString = options[8];

        Boolean errorWithStackTrace = false;

        try {
            Integer queryTimeoutValue = Integer.parseInt(qTimeoutString);
            Integer queryFetchSizeValue = qFetchSizeString == null ? null : Integer.parseInt(qFetchSizeString);
            errorWithStackTrace = Boolean.valueOf(errorWithStackTraceString);


            File jarFile = new File(fileName);
            if (!jarFile.exists()) {
                throw new RuntimeException("JDBC driver jar file does not exist: " + fileName);
            }
            URL jarfile_url = jarFile.toURI().toURL();

            if (jdbcDriverLoader == null) {
                /* If jdbcDriverLoader is being created. */
                jdbcDriverLoader = new JDBCDriverLoader(new URL[] {jarfile_url});
            } else if (jdbcDriverLoader.CheckIfClassIsLoaded(driverClassName) == null) {
                jdbcDriverLoader.appendURL(jarfile_url);
            }

            /* Make connection */
            jdbcDriverClass = jdbcDriverLoader.loadClass(driverClassName);
            jdbcDriver = (Driver) jdbcDriverClass.getDeclaredConstructor().newInstance();
            jdbcProperties = new Properties();
            if (qJdbcPropsString != null) {
                jdbcProperties.loadFromXML(new ByteArrayInputStream(qJdbcPropsString.getBytes()));
            }
            if (userName != null) jdbcProperties.put("user", userName);
            if (password != null) jdbcProperties.put("password", password);
            Connection conn = jdbcDriver.connect(url, jdbcProperties);

            if (conn == null)
                throw new SQLException("Cannot connect server: " + url);

            /* Try to get database metadata */
            @SuppressWarnings("unused")
            DatabaseMetaData dbMetadata = conn.getMetaData();

            JDBCConnection Jconn = new JDBCConnection(
                conn, 
                false, 
                server_hashvalue, 
                mapping_hashvalue, 
                queryTimeoutValue,
                queryFetchSizeValue,
                errorWithStackTrace
                );

            /* cache new connection */
            ConnectionHash.put(key, Jconn);

            return Jconn;
        } catch (Throwable e) {
            if (errorWithStackTrace) {
                throw new RuntimeException(stackTraceToString(e), e);
            }
            throw e;
        }
    }

    public static String stackTraceToString(Throwable e) {
        StringWriter exceptionStringWriter = new StringWriter();
        PrintWriter exceptionPrintWriter = new PrintWriter(exceptionStringWriter);
        e.printStackTrace(exceptionPrintWriter);
        return exceptionStringWriter.toString();
    }

}
