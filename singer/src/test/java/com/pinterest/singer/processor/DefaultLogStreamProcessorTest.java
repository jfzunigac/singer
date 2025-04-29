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
package com.pinterest.singer.processor;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.pinterest.singer.common.LogStream;
import com.pinterest.singer.common.LogStreamReader;
import com.pinterest.singer.common.errors.LogStreamProcessorException;
import com.pinterest.singer.common.LogStreamWriter;
import com.pinterest.singer.common.errors.LogStreamWriterException;
import com.pinterest.singer.common.SingerLog;
import com.pinterest.singer.common.SingerSettings;
import com.pinterest.singer.config.Decider;
import com.pinterest.singer.monitor.LogStreamManager;
import com.pinterest.singer.reader.DefaultLogStreamReader;
import com.pinterest.singer.reader.ThriftLogFileReaderFactory;
import com.pinterest.singer.thrift.LogMessage;
import com.pinterest.singer.thrift.LogMessageAndPosition;
import com.pinterest.singer.thrift.LogPosition;
import com.pinterest.singer.thrift.configuration.FileNameMatchMode;
import com.pinterest.singer.thrift.configuration.SingerConfig;
import com.pinterest.singer.thrift.configuration.SingerLogConfig;
import com.pinterest.singer.thrift.configuration.ThriftReaderConfig;
import com.pinterest.singer.utils.SimpleThriftLogger;
import com.pinterest.singer.utils.SingerUtils;
import com.pinterest.singer.utils.WatermarkUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.commons.io.FilenameUtils;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultLogStreamProcessorTest extends com.pinterest.singer.SingerTestBase {

  LogStreamReader logStreamReader;
  DefaultLogStreamProcessor processor;
  NoOpLogStreamWriter writer;

  /**
   * No-op implementation of LogStreamWriter which collect all LogMessages in a list.
   */
  private static final class NoOpLogStreamWriter implements LogStreamWriter {

    private final List<LogMessage> logMessages;

    private boolean throwOnWrite;

    public NoOpLogStreamWriter() {
      logMessages = Lists.newArrayList();
      throwOnWrite = false;
    }

    @Override
    public LogStream getLogStream() {
      return null;
    }

    @Override
    public boolean isAuditingEnabled() {
      return false;
    }

    @Override
    public void writeLogMessages(List<LogMessage> logMessages) throws LogStreamWriterException {
      if (throwOnWrite) {
        throw new LogStreamWriterException("Write error");
      } else {
        this.logMessages.addAll(logMessages);
      }
    }

    @Override
    public void close() throws IOException {
    }

    public List<LogMessage> getLogMessages() {
      return logMessages;
    }

    public void setThrowOnWrite(boolean throwOnWrite) {
      this.throwOnWrite = throwOnWrite;
    }
  }

  private void initializeReaderAndProcessor(Map<String, String> overrides, LogStream logStream) {
    Map<String, String> propertyMap = new HashMap<>();
    propertyMap.put("processorBatchSize", "50");
    propertyMap.put("processingIntervalInMillisMin", "1");
    propertyMap.put("processingIntervalInMillisMax", "1");
    propertyMap.put("processingTimeSliceInMilliseconds", "3600");
    propertyMap.put("logRetentionInSecs", "15");
    propertyMap.put("readerBufferSize", "16000");
    propertyMap.put("maxMessageSize", "16000");
    propertyMap.put("logDecider", null);

    if (overrides != null) {
      propertyMap.putAll(overrides);
    }

    logStreamReader = new DefaultLogStreamReader(
        logStream, new ThriftLogFileReaderFactory(
        new ThriftReaderConfig(Integer.valueOf(propertyMap.get("readerBufferSize")),
            Integer.valueOf(propertyMap.get("maxMessageSize")))));
    processor = new DefaultLogStreamProcessor(logStream, propertyMap.get("logDecider"),
        logStreamReader, writer, Integer.valueOf(propertyMap.get("processorBatchSize")),
        Integer.valueOf(propertyMap.get("processingIntervalInMillisMin")),
        Integer.valueOf(propertyMap.get("processingIntervalInMillisMax")),
        Integer.valueOf(propertyMap.get("processingTimeSliceInMilliseconds")),
        Integer.valueOf(propertyMap.get("logRetentionInSecs")));
  }

  private SingerConfig initializeSingerConfig(int processorThreadPoolSize, int writerThreadPoolSize,
      List<SingerLogConfig> singerLogConfigs) {
    SingerConfig singerConfig = new SingerConfig();
    singerConfig.setThreadPoolSize(1);
    singerConfig.setWriterThreadPoolSize(1);
    singerConfig.setLogConfigs(singerLogConfigs);
    return singerConfig;
  }

  @After
  public void cleanup() throws IOException {
    if (processor != null) {
      processor.close();
    }
    writer = null;
    processor = null;
    logStreamReader = null;
  }

  @Test
  public void testProcessKeyedLogStream() throws Exception {
    testProcessLogStream(true);
  }

  @Test
  public void testProcessNonKeyedLogStream() throws Exception {
    testProcessLogStream(false);
  }

  public void testProcessLogStream(boolean isKeyed) throws Exception {
    String tempPath = getTempPath();
    String logStreamHeadFileName = "thrift.log";
    String path = FilenameUtils.concat(tempPath, logStreamHeadFileName);

    int oldestThriftLogIndex = 0;
    int processorBatchSize = 50;

    // initialize a singer log config
    SingerLogConfig logConfig = new SingerLogConfig("test", tempPath, logStreamHeadFileName, null, null, null);
    SingerLog singerLog = new SingerLog(logConfig);
    singerLog.getSingerLogConfig().setFilenameMatchMode(FileNameMatchMode.PREFIX);

    // initialize global variables in SingerSettings
    try {
      SingerConfig singerConfig = initializeSingerConfig(1, 1, Arrays.asList(logConfig));
      SingerSettings.initialize(singerConfig);
    } catch (Exception e) {
      e.printStackTrace();
      fail("got exception in test: " + e);
    }

    // initialize log stream
    LogStream logStream = new LogStream(singerLog, logStreamHeadFileName);
    LogStreamManager.addLogStream(logStream);
    SimpleThriftLogger<LogMessage> logger = new SimpleThriftLogger<>(path);
    writer = new NoOpLogStreamWriter();

    // initialize reader, writer & processor
    initializeReaderAndProcessor(Collections.singletonMap("processorBatchSize", String.valueOf(processorBatchSize)), logStream);

    try {
      // Write messages to be skipped.
      if (isKeyed)
        writeThriftLogMessages(logger, 150, 500, 50);
      else
        writeThriftLogMessages(logger, 150, 50);

      // Save start position to watermark file.
      LogPosition startPosition = new LogPosition(logger.getLogFile(), logger.getByteOffset());
      WatermarkUtils.saveCommittedPositionToWatermark(DefaultLogStreamProcessor
              .getWatermarkFilename(logStream), startPosition);

      List<LogMessage> messagesWritten = Lists.newArrayList();

      // Rotate log file while writing messages.
      for (int i = 0; i < 3; ++i) {
        rotateWithDelay(logger, 1000);
        List<LogMessageAndPosition> logMessageAndPositions = isKeyed ?
                writeThriftLogMessages(logger, processorBatchSize + 20, 500, 50) :
                writeThriftLogMessages(logger, processorBatchSize + 20, 50);
        List<LogMessage> logMessages = getMessages(logMessageAndPositions);
        messagesWritten.addAll(logMessages);
      }

      waitForFileSystemEvents(logStream);

      // Process all message written so far.
      long numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should have processed all messages written", messagesWritten.size(),
              numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));

      // Write and process a single LogMessages.
      messagesWritten.addAll(getMessages(isKeyed ?
              writeThriftLogMessages(logger, 1, 500, 50) :
              writeThriftLogMessages(logger, 1, 50))
      );
      numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should have processed a single log message", 1, numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));

      // Write another set of LogMessages.
      messagesWritten.addAll(getMessages(isKeyed ?
              writeThriftLogMessages(logger, processorBatchSize + 1, 500, 50) :
              writeThriftLogMessages(logger, processorBatchSize + 1, 50))
      );

      // Writer will throw on write.
      writer.setThrowOnWrite(true);
      LogPosition positionBefore = WatermarkUtils.loadCommittedPositionFromWatermark(
              DefaultLogStreamProcessor.getWatermarkFilename(logStream));
      try {
        processor.processLogStream();
        fail("No exception is thrown on writer error");
      } catch (LogStreamProcessorException e) {
        // Exception is thrown.
      }
      LogPosition positionAfter = WatermarkUtils.loadCommittedPositionFromWatermark(
              DefaultLogStreamProcessor.getWatermarkFilename(logStream));
      assertEquals(positionBefore, positionAfter);

      // Write will not throw on write.
      writer.setThrowOnWrite(false);
      numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should not have processed any additional messages",
              processorBatchSize + 1, numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));

      // Rotate and write twice before processing
      rotateWithDelay(logger, 1000);
      boolean successfullyAdded = messagesWritten.addAll(getMessages(isKeyed ?
              writeThriftLogMessages(logger, processorBatchSize - 20, 500, 50) :
              writeThriftLogMessages(logger, processorBatchSize - 20, 50))
      );
      assertTrue(successfullyAdded);
      rotateWithDelay(logger, 1000);
      successfullyAdded = messagesWritten.addAll(getMessages(isKeyed ?
              writeThriftLogMessages(logger, processorBatchSize, 500, 50) :
              writeThriftLogMessages(logger, processorBatchSize, 50))
      );
      assertTrue(successfullyAdded);

      // Need to wait for some time to make sure that messages have been written to disk
      Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
      numOfMessageProcessed = processor.processLogStream();

      Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
      processor.processLogStream();

      assertEquals(2 * processorBatchSize - 20, numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));
      processor.processLogStream();
      String oldThriftLogPath = FilenameUtils.concat(getTempPath(), "thrift.log." + oldestThriftLogIndex);
      File oldThriftLog = new File(oldThriftLogPath);
      assertFalse(oldThriftLog.exists()); // the oldest file is at least 10 seconds old now
      //oldThriftLogPath = FilenameUtils.concat(getTempPath(),
      //        "thrift.log." + (oldestThriftLogIndex - 1));
      //oldThriftLog = new File(oldThriftLogPath);
      assertFalse(oldThriftLog.exists()); // the next oldest file is at least 9 seconds old now
      assertTrue(new File(path).exists()); // make sure the newest log file is still there
    } catch (Exception e) {
      e.printStackTrace();
      fail("Got exception in test");
    } finally {
      logger.close();
      processor.close();
    }
  }

  @Test
  public void testProcessSymlinkLogStream() throws Exception {
    String tempPath = getTempPath();
    String symlinkLogStreamHeadFile = "thrift-symlink.log";
    String thriftLog = "thrift.log";
    String logPath = FilenameUtils.concat(tempPath, thriftLog);
    String symlinkPath = FilenameUtils.concat(tempPath, symlinkLogStreamHeadFile);

    int processorBatchSize = 50;

    // initialize a singer log config
    SingerLogConfig logConfig = new SingerLogConfig("test", tempPath, symlinkLogStreamHeadFile, null, null, null);
    SingerLog singerLog = new SingerLog(logConfig);
    singerLog.getSingerLogConfig().setFilenameMatchMode(FileNameMatchMode.PREFIX);

    // initialize global variables in SingerSettings
    try {
      SingerConfig singerConfig = initializeSingerConfig(1, 1, Arrays.asList(logConfig));
      SingerSettings.initialize(singerConfig);
    } catch (Exception e) {
      e.printStackTrace();
      fail("got exception in test: " + e);
    }

    SimpleThriftLogger<LogMessage> logger = new SimpleThriftLogger<>(logPath);
    writer = new NoOpLogStreamWriter();

    // Write some messages and wait
    for (int i = 0; i < 2; ++i) {
      writeThriftLogMessages(logger, processorBatchSize * 2, 50);
      Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
    }

    Files.createSymbolicLink(new File(symlinkPath).toPath(), new File(logPath).toPath());

    // initialize log stream
    LogStream logStream = new LogStream(singerLog, symlinkLogStreamHeadFile);
    LogStreamManager.addLogStream(logStream);

    initializeReaderAndProcessor(Collections.singletonMap("processorBatchSize", String.valueOf(processorBatchSize)), logStream);

    waitForFileSystemEvents(logStream);

    // Process everything in the stream so far
    long numOfMessageProcessed = processor.processLogStream();
    assertEquals(processorBatchSize * 4, numOfMessageProcessed);


    // Delete the underlying thrift log, even if we wait after this the log stream
    // paths will not be updated by the FSM since the underlying log file is not tracked in the log stream paths
    Files.delete(new File(logPath).toPath());
    numOfMessageProcessed = processor.processLogStream();
    assertEquals(0, numOfMessageProcessed);

    // Recreate logger and write some messages
    logger = new SimpleThriftLogger<>(logPath);
    writeThriftLogMessages(logger, processorBatchSize, 50);
    Thread.sleep(FILE_EVENT_WAIT_TIME_MS);

    LogPosition positionBefore = WatermarkUtils.loadCommittedPositionFromWatermark(
        DefaultLogStreamProcessor.getWatermarkFilename(logStream));
    try {
      processor.processLogStream();
    } catch (LogStreamProcessorException e) {
      // Exception is expected until we are able to gracefully handle symlink rotations
    } finally {
      processor.processLogStream();
    }
    LogPosition positionAfter = WatermarkUtils.loadCommittedPositionFromWatermark(
        DefaultLogStreamProcessor.getWatermarkFilename(logStream));
    assertNotEquals(positionBefore, positionAfter);
  }

  public void testSymlinkRotations() throws Exception {
    String tempPath = getTempPath();
    File tmpTarget = new File(tempPath + "/target");
    tmpTarget.mkdirs();
    String symlinkLogStreamHeadFile = "thrift-symlink.log";
    String thriftLog = "thrift.log";
    String logPath = FilenameUtils.concat(tmpTarget.toString(), thriftLog);
    String symlinkPath = FilenameUtils.concat(tempPath, symlinkLogStreamHeadFile);

    int processorBatchSize = 10;

    // initialize a singer log config
    SingerLogConfig logConfig = new SingerLogConfig("test", tempPath, symlinkLogStreamHeadFile, null, null, null);
    SingerLog singerLog = new SingerLog(logConfig);
    singerLog.getSingerLogConfig().setFilenameMatchMode(FileNameMatchMode.PREFIX);

    // initialize global variables in SingerSettings
    try {
      SingerConfig singerConfig = initializeSingerConfig(1, 1, Arrays.asList(logConfig));
      SingerSettings.initialize(singerConfig);
    } catch (Exception e) {
      e.printStackTrace();
      fail("got exception in test: " + e);
    }

    SimpleThriftLogger<LogMessage> logger = new SimpleThriftLogger<>(logPath);
    writer = new NoOpLogStreamWriter();

    // Write some messages and wait
    for (int i = 0; i < 2; ++i) {
      writeThriftLogMessages(logger, processorBatchSize, 150);
      Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
    }

    Files.createSymbolicLink(new File(symlinkPath).toPath(), new File(logPath).toPath());

    // initialize log stream
    LogStream logStream = new LogStream(singerLog, symlinkLogStreamHeadFile);
    LogStreamManager.addLogStream(logStream);

    initializeReaderAndProcessor(Collections.singletonMap("processorBatchSize", String.valueOf(processorBatchSize)), logStream);

    waitForFileSystemEvents(logStream);

    // Process everything in the stream so far
    long numOfMessagesProcessed = processor.processLogStream();
    assertEquals(processorBatchSize * 2, numOfMessagesProcessed);
    rotateWithDelay(logger, 1000);
    writeThriftLogMessages(logger, processorBatchSize * 4, 5);

    numOfMessagesProcessed = 0;
    LogPosition positionBefore = WatermarkUtils.loadCommittedPositionFromWatermark(
        DefaultLogStreamProcessor.getWatermarkFilename(logStream));
    while (numOfMessagesProcessed >= 0 && numOfMessagesProcessed < processorBatchSize * 4) {
      try {
        numOfMessagesProcessed += processor.processLogStream();
      } catch (LogStreamProcessorException e) {
        // Exception is expected until we are able to gracefully handle symlink rotations
      }
    }
    assertEquals(processorBatchSize * 4, numOfMessagesProcessed);
    LogPosition positionAfter = WatermarkUtils.loadCommittedPositionFromWatermark(
        DefaultLogStreamProcessor.getWatermarkFilename(logStream));
    assertNotEquals(positionBefore, positionAfter);
  }

  @Test
  public void testProcessLogStreamWithDecider() throws Exception {
    try {
      SingerConfig singerConfig = initializeSingerConfig(1, 1, Collections.emptyList());
      SingerSettings.initialize(singerConfig);
      SingerLog singerLog = new SingerLog(
          new SingerLogConfig("test", getTempPath(), "thrift.log", null, null, null));
      LogStream logStream = new LogStream(singerLog, "thrift.log");
      writer = new NoOpLogStreamWriter();
      initializeReaderAndProcessor(Collections.singletonMap("logDecider", "singer_test_decider"), logStream);
      Decider.setInstance(ImmutableMap.of("singer_test_decider", 0));
      // Write messages to be skipped.
      boolean deciderEnabled = processor.isLoggingAllowedByDecider();
      assertEquals(false, deciderEnabled);
    } catch (Exception e) {
      e.printStackTrace();
      fail("Unexpected exception");
    } finally {
      if (processor != null) {
        processor.close();
      }
    }
  }

  @Test
  public void testDisableDecider() throws Exception {
    SingerUtils.setHostname("localhost-prod.cluster-19970722", "[.-]");
    try {
      SingerConfig singerConfig = initializeSingerConfig(1, 1, Collections.emptyList());
      SingerSettings.initialize(singerConfig);
      SingerLog singerLog = new SingerLog(
          new SingerLogConfig("test", getTempPath(), "thrift.log", null, null, null));
      LogStream logStream = new LogStream(singerLog, "thrift.log");
      writer = new NoOpLogStreamWriter();
      initializeReaderAndProcessor(Collections.singletonMap("logDecider", "singer_test_decider"), logStream);
      Decider.setInstance(new HashMap<>());
      Decider.getInstance().getDeciderMap().put("singer_test_decider", 100);
      assertEquals(true, processor.isLoggingAllowedByDecider());

      Decider.getInstance().getDeciderMap().put("singer_disable_test___localhost___decider", 100);
      assertEquals(false, processor.isLoggingAllowedByDecider());

      Decider.getInstance().getDeciderMap().put("singer_disable_test___localhost___decider", 50);
      Decider.getInstance().getDeciderMap().put("singer_disable_test___localhost_prod_cluster___decider", 100);
      assertEquals(false, processor.isLoggingAllowedByDecider());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Unexpected exception");
    } finally {
      if (processor != null) {
        processor.close();
      }
    }
    SingerUtils.setHostname(SingerUtils.getHostname(), "-");
  }

  @Test
  public void testRegexDirLogStreamInitializationWithTwoLogStreamsInSeries() throws Exception {
    String baseDir = getTempPath();
    String logStreamRegex = "singer_testing_(\\d+).log.0";
    String varDirWithPattern = baseDir + "/var/log/*";

    SingerLogConfig logConfig = new SingerLogConfig(
        "test_log_stream",
        varDirWithPattern, // Regex pattern that matches multiple directories
        logStreamRegex,
        null,
        null,
        null
    );
    logConfig.setFilenameMatchMode(FileNameMatchMode.PREFIX);

    List<SingerLogConfig> logConfigs = Collections.singletonList(logConfig);
    SingerConfig singerConfig = initializeSingerConfig(1, 1, logConfigs);
    SingerSettings.initialize(singerConfig);

    LogStreamManager.initializeLogStreams();

    Thread.sleep(3000);

    String newDirPath1 = baseDir + "/var/log/tmp1";
    Files.createDirectories(Paths.get(newDirPath1));

    Thread.sleep(3000);

    String newLogFilePath1 = FilenameUtils.concat(newDirPath1, "singer_testing_0.log.0");
    SimpleThriftLogger<LogMessage> logger1 = new SimpleThriftLogger<>(newLogFilePath1);

    writeThriftLogMessages(logger1, 10, 50);
    logger1.close();

    LogStreamManager.initializeLogStreams();

    // Verify log streams for the first directory
    Collection<LogStream> logStreams1 = LogStreamManager.getLogStreams(Paths.get(newDirPath1));
    assertNotNull(logStreams1);
    assertFalse(logStreams1.isEmpty());
    assertTrue(logStreams1.size() == 1);

    System.out.println(LogStreamManager.getInstance().getSingerLogPaths());

    // Operations for the second directory
    String newDirPath2 = baseDir + "/var/log/tmp2";
    Files.createDirectories(Paths.get(newDirPath2));

    Thread.sleep(3000);

    String newLogFilePath2 = FilenameUtils.concat(newDirPath2, "singer_testing_0.log.0");
    SimpleThriftLogger<LogMessage> logger2 = new SimpleThriftLogger<>(newLogFilePath2);

    writeThriftLogMessages(logger2, 10, 50);
    logger2.close();

    LogStreamManager.initializeLogStreams();

    Collection<LogStream> logStreams2 = LogStreamManager.getLogStreams(Paths.get(newDirPath2));
    assertNotNull(logStreams2);
    assertFalse(logStreams2.isEmpty());
    assertTrue(logStreams2.size() == 1);

    System.out.println(LogStreamManager.getInstance().getSingerLogPaths());
  }

  @Test
  public void testRegexDirLogStreamInitializationWithTwoLogStreamsInParallel() throws Exception {
    String baseDir = getTempPath();
    String logStreamRegex = "singer_testing_(\\d+).log.0";
    String varDirWithPattern = baseDir + "/var/*";

    // Step 1: Using the existing initialization style with new logDir and regex pattern
    SingerLogConfig logConfig = new SingerLogConfig(
        "test_log_stream",
        varDirWithPattern, // Regex pattern that matches multiple directories
        logStreamRegex,
        null,
        null,
        null
    );
    logConfig.setFilenameMatchMode(FileNameMatchMode.PREFIX);

    // Initialize SingerConfig and SingerSettings
    List<SingerLogConfig> logConfigs = Collections.singletonList(logConfig);
    SingerConfig singerConfig = initializeSingerConfig(1, 1, logConfigs);
    SingerSettings.initialize(singerConfig);

    // Initialize LogStreamManager
    LogStreamManager.initializeLogStreams();

    // Simulate delay
    Thread.sleep(3000);

    // Step 2: Dynamically create directories
    String newDirPath1 = baseDir + "/var/log";
    String newDirPath2 = baseDir + "/var/log1";
    Files.createDirectories(Paths.get(newDirPath1));
    Files.createDirectories(Paths.get(newDirPath2));

    // Simulate delay to ensure directories are registered
    Thread.sleep(3000);

    // Step 3: Dynamically create log files in both new directories
    String newLogFilePath1 = FilenameUtils.concat(newDirPath1, "singer_testing_0.log.0");
    String newLogFilePath2 = FilenameUtils.concat(newDirPath2, "singer_testing_0.log.0");

    // Write messages to newly created log files
    SimpleThriftLogger<LogMessage> logger1 = new SimpleThriftLogger<>(newLogFilePath1);
    SimpleThriftLogger<LogMessage> logger2 = new SimpleThriftLogger<>(newLogFilePath2);

    // Write log messages
    writeThriftLogMessages(logger1, 10, 50);
    writeThriftLogMessages(logger2, 10, 50);
    logger1.close();
    logger2.close();

    // Re-run initializeLogStreams to capture any newly created directories and files
    LogStreamManager.initializeLogStreams();

    // Step 4: Verify LogStream initialization
    Collection<LogStream> logStreams1 = LogStreamManager.getLogStreams(Paths.get(newDirPath1));
    Collection<LogStream> logStreams2 = LogStreamManager.getLogStreams(Paths.get(newDirPath2));

    // Verify log streams for directory 1
    assertNotNull(logStreams1);
    assertFalse(logStreams1.isEmpty());
    assertTrue(logStreams1.size() == 1);
    System.out.println(LogStreamManager.getInstance().getSingerLogPaths());
    // Verify log streams for directory 2
    assertNotNull(logStreams2);
    assertFalse(logStreams2.isEmpty());
    assertTrue(logStreams2.size() == 1);
    System.out.println(LogStreamManager.getInstance().getSingerLogPaths());
  }


  @Test
  public void testRegexDirLogStreamInitialization() throws Exception {
    String baseDir = getTempPath();
    String logStreamRegex = "singer_testing_(\\d+).log.0";
    String varDirWithPattern = baseDir + "/var/*";

    // Step 1: Using the existing initialization style with new logDir and regex pattern
    SingerLogConfig logConfig = new SingerLogConfig(
        "test_log_stream",
        varDirWithPattern,
        logStreamRegex,
        null,
        null,
        null
    );
    logConfig.setFilenameMatchMode(FileNameMatchMode.PREFIX);

    List<SingerLogConfig> logConfigs = Collections.singletonList(logConfig);
    SingerConfig singerConfig = initializeSingerConfig(1, 1, logConfigs);
    SingerSettings.initialize(singerConfig);

    // Initialize LogStreamManager
    LogStreamManager.initializeLogStreams();

    // Simulate delay
    Thread.sleep(3000);

    // Step 2: Create and register a new directory and file after a delay
    String newDirPath = baseDir + "/var/log";
    Files.createDirectories(Paths.get(newDirPath));

    // Simulate delay
    Thread.sleep(3000);

    String newLogFilePath = FilenameUtils.concat(newDirPath, "singer_testing_2.log.0");
    SimpleThriftLogger<LogMessage> logger = new SimpleThriftLogger<>(newLogFilePath);

    // Step 3: Write log messages to newly created log file
    writeThriftLogMessages(logger, 10, 50);
    logger.close();

    LogStreamManager.initializeLogStreams();

    // Step 4: Verify LogStream initialization
    Collection<LogStream> logStreams = LogStreamManager.getLogStreams(Paths.get(newDirPath));
    assertNotNull(logStreams);
    assertFalse(logStreams.isEmpty());
    assertTrue(logStreams.size() == 1);
  }

  private static List<LogMessage> getMessages(List<LogMessageAndPosition> messageAndPositions) {
    List<LogMessage> messages = Lists.newArrayListWithExpectedSize(messageAndPositions.size());
    for (LogMessageAndPosition messageAndPosition : messageAndPositions) {
      messages.add(messageAndPosition.getLogMessage());
    }
    return messages;
  }

  /*
   * Added to enable running this test on OS X
   */
  private static void waitForFileSystemEvents(LogStream logStream) throws InterruptedException {
    while (logStream.isEmpty()) {
      Thread.sleep(1000);
      System.out.print(".");
    }
  }
}