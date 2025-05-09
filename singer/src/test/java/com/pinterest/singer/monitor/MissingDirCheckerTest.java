/**
 * Copyright 2019 Pinterest, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.singer.monitor;

import com.pinterest.singer.SingerTestBase;
import com.pinterest.singer.common.SingerSettings;
import com.pinterest.singer.config.DirectorySingerConfigurator;
import com.pinterest.singer.thrift.configuration.SingerConfig;

import com.pinterest.singer.utils.LogConfigUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


public class MissingDirCheckerTest extends SingerTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(MissingDirCheckerTest.class);

  @Before
  public void setUp() throws SecurityException, NoSuchFieldException, IllegalArgumentException,
                             IllegalAccessException, IOException {
    tempDir.create();
    logConfigDir = tempDir.newFolder(DirectorySingerConfigurator.SINGER_LOG_CONFIG_DIR);

    LOG.info("Get and reset LogStreamManager instance (singleton) to make sure that instance used"
        + " in unit test is specifically set for this unit test");
    LogStreamManager.getInstance();
    LogStreamManager.reset();
  }

  @After
  public void tearDown() throws IOException {
    File testBaseDir= tempDir.getRoot();
    LOG.info("Clean up files under test base dir: " + testBaseDir.getAbsolutePath());
    if (testBaseDir.exists()) {
      FileUtils.deleteDirectory(testBaseDir);
    }
  }

  /**
   *  This test is to verify MissingDirChecker can track all SingerLog whose log dir was not
   *  created before Singer is started. After log dir for certain SingerLog was created,
   *  MissingDirChecker will call method to properly initialize log stream for this SingerLog and
   *  remove this SingerLog from the tracking hash map maintained by MissingDirChecker.
   *
   *  If this test fails, it might be the issue that after log dir was created for certain
   *  SingerLog, MissingDirChecker has not had chance to call method to initialize log stream and
   *  remove this SingerLog from the hash map. One fix is to let the Thread sleep longer, eg:
   *  Thread.sleep(instance.getMissingDirChecker().getSleepInMills() * 5);
   * @throws Exception
   */
  @Test
  public void testMissingDirChecker() throws Exception{
    String testBasePath = tempDir.getRoot().getAbsolutePath();

    LOG.info("Create test singer property file and singer log config property file.");
    Map<String, String> singerConfigProperties = makeDirectorySingerConfigProperties("");
    File singerConfigFile = createSingerConfigFile(singerConfigProperties);
    String[] propertyFileNames = {"test.app1.properties",  "test.app2.properties",
                                  "test.app3.properties", "test.app4.properties",};
    String[] logStreamRegexes = {"app1_(\\\\w+)", "app2_(\\\\w+)", "app3_(\\\\w+)", "app4_(\\\\w+)"};
    String[] logDirPaths ={"/mnt/log/singer/", "/mnt/thrift_logger/", "/x/y/z/", "/a/b/c"};
    String[] topicNames = {"topic1", "topic2", "topic3", "topic4"};
    File[] files = new File[logDirPaths.length];
    for(int i = 0; i < logDirPaths.length; i++) {
      logDirPaths[i] = new File(testBasePath, logDirPaths[i]).getAbsolutePath();
      files[i] = createSingerLogConfigPropertyFile(propertyFileNames[i], logStreamRegexes[i],
          logDirPaths[i], topicNames[i]);
    }

    LOG.info("Try to parse SingerConfig.");
    String parentDirPath = singerConfigFile.getParent();
    DirectorySingerConfigurator configurator = new DirectorySingerConfigurator(parentDirPath);
    SingerConfig singerConfig = configurator.parseSingerConfig();

    LOG.info("Create LogStreamManager instance (Singleton).");
    SingerSettings.setSingerConfig(singerConfig);
    LogStreamManager instance = LogStreamManager.getInstance();
    LogStreamManager.initializeLogStreams();

    // setting sleep time of MissingDirChecker to be 5 seconds in order to accelerate unit test.
    instance.getMissingDirChecker().setSleepInMills(5000);

    int numOfSingerLogsWithoutDir = logDirPaths.length;
    assertEquals(numOfSingerLogsWithoutDir, instance.getMissingDirChecker().getSingerLogsWithoutDir().size());

    LOG.info("Verify MissingDirChecker runs properly while logDir is created for each SingerLog");
    for(String path : logDirPaths) {
      File f1 = new File(path);
      f1.mkdirs();
      assertTrue(f1.exists() && f1.isDirectory());
      Thread.sleep(instance.getMissingDirChecker().getSleepInMills() * 2);
      numOfSingerLogsWithoutDir -= 1;
      LOG.info("verify the number match: {} {}", numOfSingerLogsWithoutDir,
          instance.getMissingDirChecker().getSingerLogsWithoutDir().size());
      assertEquals(numOfSingerLogsWithoutDir, instance.getMissingDirChecker().getSingerLogsWithoutDir().size());
    }
    LOG.info("Verify MissingDirChecker thread can be stopped properly.");
    assertFalse(instance.getMissingDirChecker().getCancelled().get());
    instance.stop();
    assertTrue(instance.getMissingDirChecker().getCancelled().get());

  }

  @Test
  public void testDynamicDirectoryDiscovery() throws Exception{
    String testBasePath = tempDir.getRoot().getAbsolutePath();

    LOG.info("Create test singer property file and singer log config property file.");
    Map<String, String> singerConfigProperties = makeDirectorySingerConfigProperties("");
    File singerConfigFile = createSingerConfigFile(singerConfigProperties);
    List<String> propertyFileNames = Arrays.asList("test.app1.properties", "test.app2.properties");
    List<String> logStreamRegexes = Arrays.asList("app1_(\\\\w+)", "app2_(\\\\w+)");
    List<String>
        logDirPaths = Arrays.asList("/mnt/log_*/singer/", "/mnt/service/*", "/var/log");
    logDirPaths.replaceAll(path -> testBasePath + path); // append base path
    List<String> topicNames = Arrays.asList("topic1", "topic2");

    List<File> propertiesFiles = Arrays.asList(
        createSingerLogConfigPropertyFile(propertyFileNames.get(0), logStreamRegexes.get(0),
            logDirPaths.get(0) + "," + logDirPaths.get(1), topicNames.get(0)),
        createSingerLogConfigPropertyFile(propertyFileNames.get(1), logStreamRegexes.get(1),
            logDirPaths.get(2), topicNames.get(1)));

    LOG.info("Try to parse SingerConfig.");
    String parentDirPath = singerConfigFile.getParent();
    DirectorySingerConfigurator configurator = new DirectorySingerConfigurator(parentDirPath);
    SingerConfig singerConfig = configurator.parseSingerConfig();

    LOG.info("Create LogStreamManager instance (Singleton).");
    SingerSettings.setSingerConfig(singerConfig);
    LogStreamManager instance = LogStreamManager.getInstance();
    LogStreamManager.initializeLogStreams();

    // setting sleep time of MissingDirChecker to be 5 seconds in order to accelerate unit test.
    instance.getMissingDirChecker().setSleepInMills(5000);

    int numOfSingerLogsWithoutDir = propertiesFiles.size(); // +1 for the two directories in one config
    assertEquals(numOfSingerLogsWithoutDir, instance.getMissingDirChecker().getSingerLogsWithoutDir().size());
    LOG.info("Verify MissingDirChecker runs properly while logDir is created for each SingerLog");

    // Create n directories for /mnt/log_*/singer/ and /mnt/service/*
    for (int i = 0; i < 3; i++) {
      String path = logDirPaths.get(i).replace("*", String.valueOf(i));
      File f1 = new File(path);
      f1.mkdirs();
      assertTrue(f1.exists() && f1.isDirectory());
      Thread.sleep(instance.getMissingDirChecker().getSleepInMills() * 2);
      LOG.info("verify the number match: {} {}", numOfSingerLogsWithoutDir,
          instance.getMissingDirChecker().getSingerLogsWithoutDir().size());
      if (i == 2) {
        numOfSingerLogsWithoutDir -= 1; // only static directory should be removed from list
      }
      assertEquals(numOfSingerLogsWithoutDir, instance.getMissingDirChecker().getSingerLogsWithoutDir().size());
    }
    LOG.info("Verify MissingDirChecker thread can be stopped properly.");
    assertFalse(instance.getMissingDirChecker().getCancelled().get());
    instance.stop();
    assertTrue(instance.getMissingDirChecker().getCancelled().get());
  }

  public Map<String, String> makeDirectorySingerConfigProperties(String logConfigDirPath) {
    return new TreeMap<String, String>() {
      private static final long serialVersionUID = 1L;

      {
        put("singer.threadPoolSize", "8");
        put("singer.ostrichPort", "9896");
        put("singer.monitor.monitorIntervalInSecs", "10");
        put("singer.logConfigDir", logConfigDir.getPath());
        put("singer.logConfigPollIntervalSecs", "1");
        put("singer.logRetentionInSecs", "172800");
        put("singer.heartbeatEnabled", "false");
      }
    };
  }

  public File createSingerLogConfigPropertyFile(String propertyFileName,
                                                String logStreamRegex,
                                                String logDirPath,
                                                String topicName) throws IOException{
    Map<String, String> override = new HashMap<String, String>();
    override.put("writer.kafka.producerConfig.bootstrap.servers", "127.0.0.1:9092");
    override.put("logDir", logDirPath);
    override.put("logStreamRegex", logStreamRegex);
    override.put("writer.kafka.topic", topicName);
    return  createLogConfigPropertiesFile(propertyFileName, override);
  }

  @Test
  public void testWildCardDirectoryDiscovery() throws Exception {
    // Setup directory structure:
    // tempDir/
    //   ├── test_1
    //   │   ├── session_123
    //   │   │   └── logs
    //   │   └── session_456
    //   │       ├── logs
    //   │       └── unrelated
    //   └── test_2
    //       └── session_789
    //           └── logs
    //           └── deep
    //               └── logs
    Set<String> logDirs = new HashSet<>();
    String tempDir = getTempPath();
    Path firstBaseDir = Files.createDirectory(Paths.get(tempDir + "/test_1"));

    Path firstSessionSubDir = Files.createDirectory(Paths.get(firstBaseDir + "/session_123"));
    logDirs.add(Files.createDirectory(Paths.get(firstSessionSubDir + "/logs")).toString());
    Path secondSessionSubDir = Files.createDirectory(Paths.get(firstBaseDir + "/session_456"));
    logDirs.add(Files.createDirectory(Paths.get(secondSessionSubDir + "/logs")).toString());
    Files.createDirectory(Paths.get(secondSessionSubDir + "/unrelated"));

    Path secondBaseDir = Files.createDirectory(Paths.get(tempDir + "/test_2"));
    Path thirdSessionSubDir = Files.createDirectory(Paths.get(secondBaseDir + "/session_789"));
    logDirs.add(Files.createDirectory(Paths.get(thirdSessionSubDir + "/logs")).toString());

    Files.createDirectories(Paths.get(thirdSessionSubDir + "/deep/logs"));

    String pattern = tempDir + "/*/session_*/logs";

    Set<String> result = LogConfigUtils.wildcardDirectoryMatcher(pattern, Paths.get(tempDir));
    assertEquals(logDirs, result);
  }
}


