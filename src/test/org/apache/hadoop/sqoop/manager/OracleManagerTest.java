/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.sqoop.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.ArrayList;
import java.text.ParseException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.testutil.CommonArgs;
import org.apache.hadoop.sqoop.testutil.ImportJobTestCase;
import org.apache.hadoop.sqoop.util.FileListing;

/**
 * Test the OracleManager implementation.
 *
 * This uses JDBC to import data from an Oracle database into HDFS.
 *
 * Since this requires an Oracle installation on your local machine to use,
 * this class is named in such a way that Hadoop's default QA process does
 * not run it. You need to run this manually with -Dtestcase=OracleManagerTest.
 *
 * You need to put Oracle's JDBC driver library (ojdbc6_g.jar) in a location
 * where Hadoop will be able to access it (since this library cannot be checked
 * into Apache's tree for licensing reasons).
 *
 * To set up your test environment:
 *   Install Oracle Express Edition 10.2.0.
 *   Create a database user named SQOOPTEST
 *   Set the user's password to '12345'
 *   Grant the user the CREATE TABLE privilege
 */
public class OracleManagerTest extends ImportJobTestCase {

  public static final Log LOG = LogFactory.getLog(OracleManagerTest.class.getName());

  static final String ORACLE_DATABASE_NAME = "xe"; // Express edition hardcoded name.
  static final String TABLE_NAME = "EMPLOYEES";
  static final String CONNECT_STRING = "jdbc:oracle:thin:@//localhost/" + ORACLE_DATABASE_NAME;
  static final String ORACLE_USER_NAME = "SQOOPTEST";
  static final String ORACLE_USER_PASS = "12345";

  // instance variables populated during setUp, used during tests
  private OracleManager manager;

  @Before
  public void setUp() {
    SqoopOptions options = new SqoopOptions(CONNECT_STRING, TABLE_NAME);
    options.setUsername(ORACLE_USER_NAME);
    options.setPassword(ORACLE_USER_PASS);

    manager = new OracleManager(options);

    Connection connection = null;
    Statement st = null;

    try {
      connection = manager.getConnection();
      connection.setAutoCommit(false);
      st = connection.createStatement();

      // create the database table and populate it with data. 
      st.executeUpdate("BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + TABLE_NAME + "'; "
          + "exception when others then null; end;");
      st.executeUpdate("CREATE TABLE " + TABLE_NAME + " ("
          + "id INT NOT NULL, "
          + "name VARCHAR2(24) NOT NULL, "
          + "start_date DATE, "
          + "salary FLOAT, "
          + "dept VARCHAR2(32), "
          + "timestamp_tz TIMESTAMP WITH TIME ZONE, "
          + "timestamp_ltz TIMESTAMP WITH LOCAL TIME ZONE, "
          + "PRIMARY KEY (id))");

      st.executeUpdate("INSERT INTO " + TABLE_NAME + " VALUES("
          + "1,'Aaron',to_date('2009-05-14','yyyy-mm-dd'),1000000.00,'engineering','29-DEC-09 12.00.00.000000000 PM','29-DEC-09 12.00.00.000000000 PM')");
      st.executeUpdate("INSERT INTO " + TABLE_NAME + " VALUES("
          + "2,'Bob',to_date('2009-04-20','yyyy-mm-dd'),400.00,'sales','30-DEC-09 12.00.00.000000000 PM','30-DEC-09 12.00.00.000000000 PM')");
      st.executeUpdate("INSERT INTO " + TABLE_NAME + " VALUES("
          + "3,'Fred',to_date('2009-01-23','yyyy-mm-dd'),15.00,'marketing','31-DEC-09 12.00.00.000000000 PM','31-DEC-09 12.00.00.000000000 PM')");
      connection.commit();
    } catch (SQLException sqlE) {
      LOG.error("Encountered SQL Exception: " + sqlE);
      sqlE.printStackTrace();
      fail("SQLException when running test setUp(): " + sqlE);
    } finally {
      try {
        if (null != st) {
          st.close();
        }

        if (null != connection) {
          connection.close();
        }
      } catch (SQLException sqlE) {
        LOG.warn("Got SQLException when closing connection: " + sqlE);
      }
    }
  }

  @After
  public void tearDown() {
    try {
      manager.close();
    } catch (SQLException sqlE) {
      LOG.error("Got SQLException: " + sqlE.toString());
      fail("Got SQLException: " + sqlE.toString());
    }
  }

  private String [] getArgv() {
    ArrayList<String> args = new ArrayList<String>();

    CommonArgs.addHadoopFlags(args);

    args.add("--table");
    args.add(TABLE_NAME);
    args.add("--warehouse-dir");
    args.add(getWarehouseDir());
    args.add("--connect");
    args.add(CONNECT_STRING);
    args.add("--username");
    args.add(ORACLE_USER_NAME);
    args.add("--password");
    args.add(ORACLE_USER_PASS);
    args.add("--num-mappers");
    args.add("1");

    return args.toArray(new String[0]);
  }

  private void runOracleTest(String [] expectedResults) throws IOException {

    Path warehousePath = new Path(this.getWarehouseDir());
    Path tablePath = new Path(warehousePath, TABLE_NAME);
    Path filePath = new Path(tablePath, "part-00000");

    File tableFile = new File(tablePath.toString());
    if (tableFile.exists() && tableFile.isDirectory()) {
      // remove the directory before running the import.
      FileListing.recursiveDeleteDir(tableFile);
    }

    String [] argv = getArgv();
    try {
      runImport(argv);
    } catch (IOException ioe) {
      LOG.error("Got IOException during import: " + ioe.toString());
      ioe.printStackTrace();
      fail(ioe.toString());
    }
      
    File f = new File(filePath.toString());
    assertTrue("Could not find imported data file", f.exists());
    BufferedReader r = null;
    try {
      // Read through the file and make sure it's all there.
      r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
      for (String expectedLine : expectedResults) {
        compareRecords(expectedLine, r.readLine());
      }
    } catch (IOException ioe) {
      LOG.error("Got IOException verifying results: " + ioe.toString());
      ioe.printStackTrace();
      fail(ioe.toString());
    } finally {
      IOUtils.closeStream(r);
    }
  }

  @Test
  public void testOracleImport() throws IOException {
    // no quoting of strings allowed.
    // NOTE: Oracle JDBC 11.1 drivers auto-cast SQL DATE to java.sql.Timestamp.
    // Even if you define your columns as DATE in Oracle, they may still contain
    // time information, so the JDBC drivers lie to us and will never tell us we have
    // a strict DATE type. Thus we include HH:MM:SS.mmmmm below.
    // See http://www.oracle.com/technology/tech/java/sqlj_jdbc/htdocs/jdbc_faq.html#08_01
    String [] expectedResults = {
        "1,Aaron,2009-05-14 00:00:00.0,1000000,engineering,2009-12-29 12:00:00.0,2009-12-29 12:00:00.0",
        "2,Bob,2009-04-20 00:00:00.0,400,sales,2009-12-30 12:00:00.0,2009-12-30 12:00:00.0",
        "3,Fred,2009-01-23 00:00:00.0,15,marketing,2009-12-31 12:00:00.0,2009-12-31 12:00:00.0"
    };

    runOracleTest(expectedResults);
  }

  /**
   * Compare two lines
   * @param expectedLine    expected line
   * @param receivedLine    received line
   * @throws IOException    exception during lines comparison
   */
  private void compareRecords(String expectedLine, String receivedLine) throws IOException {
    // handle null case
    if (expectedLine == null || receivedLine == null) {
      return;
    }

    // check if lines are equal
    if (expectedLine.equals(receivedLine)) {
      return;
    }

    // check if size is the same
    String [] expectedValues = expectedLine.split(",");
    String [] receivedValues = receivedLine.split(",");
    if (expectedValues.length != 7 || receivedValues.length != 7) {
      LOG.error("Number of expected fields did not match number of received fields");
      throw new IOException("Number of expected fields did not match number of received fields");
    }

    // check first 5 values
    boolean mismatch = false;
    for (int i = 0; !mismatch && i < 5; i++) {
      mismatch = !expectedValues[i].equals(receivedValues[i]);
    }
    if (mismatch) {
      throw new IOException("Expected:<" + expectedLine + "> but was:<" + receivedLine + ">");
    }

    Date expectedDate = null;
    Date receivedDate = null;
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");
    int offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000;
    for (int i = 5; i < 7; i++) {
      // parse expected timestamp
      try {
        expectedDate = df.parse(expectedValues[i]);
      } catch (ParseException ex) {
        LOG.error("Could not parse expected timestamp: " + expectedValues[i]);
        throw new IOException("Could not parse expected timestamp: " + expectedValues[i]);
      }

      // parse received timestamp
      try {
        receivedDate = df.parse(receivedValues[i]);
      } catch (ParseException ex) {
        LOG.error("Could not parse received timestamp: " + receivedValues[i]);
        throw new IOException("Could not parse received timestamp: " + receivedValues[i]);
      }

      // compare two timestamps considering timezone offset
      Calendar expectedCal = Calendar.getInstance();
      expectedCal.setTime(expectedDate);
      expectedCal.add(Calendar.HOUR, offset);

      Calendar receivedCal = Calendar.getInstance();
      receivedCal.setTime(receivedDate);

      if (!expectedCal.equals(receivedCal)) {
        throw new IOException("Expected:<" + expectedLine + "> but was:<" + receivedLine + ">, while timezone offset is: " + offset);
      }
    }
  }
}