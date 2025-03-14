/**
 * Copyright 2020 Pinterest, Inc.
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
import com.pinterest.singer.reader.TextLogFileReaderFactory;
import com.pinterest.singer.reader.ThriftLogFileReaderFactory;
import com.pinterest.singer.thrift.LogFile;
import com.pinterest.singer.thrift.LogMessage;
import com.pinterest.singer.thrift.LogMessageAndPosition;
import com.pinterest.singer.thrift.LogPosition;
import com.pinterest.singer.thrift.configuration.FileNameMatchMode;
import com.pinterest.singer.thrift.configuration.SingerConfig;
import com.pinterest.singer.thrift.configuration.SingerLogConfig;
import com.pinterest.singer.thrift.configuration.TextLogMessageType;
import com.pinterest.singer.thrift.configuration.TextReaderConfig;
import com.pinterest.singer.thrift.configuration.ThriftReaderConfig;
import com.pinterest.singer.utils.SimpleThriftLogger;
import com.pinterest.singer.utils.SingerUtils;
import com.pinterest.singer.utils.TextLogger;
import com.pinterest.singer.utils.WatermarkUtils;
import com.pinterest.singer.writer.kafka.CommittableKafkaWriter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.commons.io.FilenameUtils;
import org.junit.Test;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link CommittableKafkaWriter}
 *
 */
public class TestMemoryEfficientLogStreamProcessor extends com.pinterest.singer.SingerTestBase {

  /**
   * No-op implementation of LogStreamWriter which collect all LogMessages in a
   * list.
   */
  private static final class NoOpLogStreamWriter implements LogStreamWriter {

    private final List<LogMessage> logMessages;

    private boolean throwOnWrite;

    public NoOpLogStreamWriter() {
      logMessages = new ArrayList<>();
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
    public void startCommit(boolean isDraining) throws LogStreamWriterException {
    }

    @Override
    public void writeLogMessageToCommit(LogMessageAndPosition logMessageAndPosition,
                                        boolean isDraining) throws LogStreamWriterException {
      if (throwOnWrite) {
        throw new LogStreamWriterException("Write error");
      } else {
        this.logMessages.add(logMessageAndPosition.getLogMessage());
      }
    }

    @Override
    public void endCommit(int numLogMessagesRead, boolean isDraining) throws LogStreamWriterException {
      if (throwOnWrite) {
        throw new LogStreamWriterException("Write error");
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

  private SingerConfig initializeSingerConfig(int processorThreadPoolSize,
                                              int writerThreadPoolSize,
                                              List<SingerLogConfig> singerLogConfigs) {
    SingerConfig singerConfig = new SingerConfig();
    singerConfig.setThreadPoolSize(1);
    singerConfig.setWriterThreadPoolSize(1);
    singerConfig.setLogConfigs(singerLogConfigs);
    return singerConfig;
  }

  @Test
  public void testProcessKeyedLogStream() throws Exception {
    testProcessLogStream(true);
  }

  @Test
  public void testProcessNonKeyedLogStream() throws Exception {
    testProcessLogStream(false);
  }

  @Test
  public void testProcessDeciderBasedLogStream() throws Exception {
    boolean isKeyed = false;
    String tempPath = getTempPath();
    String logStreamHeadFileName = "thrift1.log";
    String path = FilenameUtils.concat(tempPath, logStreamHeadFileName);

    int percentage = 10;
    Map<String, Integer> deciderMap = new HashMap<>();
    String deciderName = "decider1";
    deciderMap.put(deciderName, percentage);
    Decider.setInstance(deciderMap);

    int readerBufferSize = 16000;
    int maxMessageSize = 16000;
    int processorBatchSize = 50;

    long processingIntervalInMillisMin = 1;
    long processingIntervalInMillisMax = 1;
    long processingTimeSliceInMilliseconds = 3600;
    int logRetentionInSecs = 15;

    // initialize a singer log config
    SingerLogConfig logConfig = new SingerLogConfig("test", tempPath, logStreamHeadFileName, null,
        null, null);
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
    NoOpLogStreamWriter writer = new NoOpLogStreamWriter();

    // initialize a log stream reader with 16K as readerBufferSize and
    // maxMessageSize
    LogStreamReader logStreamReader = new DefaultLogStreamReader(logStream,
        new ThriftLogFileReaderFactory(new ThriftReaderConfig(readerBufferSize, maxMessageSize)));
    // initialize a log stream processor that
    MemoryEfficientLogStreamProcessor processor = new MemoryEfficientLogStreamProcessor(logStream,
        deciderName, logStreamReader, writer, processorBatchSize, processingIntervalInMillisMin,
        processingIntervalInMillisMax, processingTimeSliceInMilliseconds, logRetentionInSecs, true);

    try {
      // Write messages to be skipped.
      if (isKeyed)
        writeThriftLogMessages(logger, 150, 500, 50);
      else
        writeThriftLogMessages(logger, 150, 50);

      // Save start position to watermark file.
      LogPosition startPosition = new LogPosition(logger.getLogFile(), logger.getByteOffset());
      WatermarkUtils.saveCommittedPositionToWatermark(
          MemoryEfficientLogStreamProcessor.getWatermarkFilename(logStream), startPosition);

      List<LogMessage> messagesWritten = Lists.newArrayList();

      // Rotate log file while writing messages.
      for (int i = 0; i < 3; ++i) {
        rotateWithDelay(logger, 1000);
        List<LogMessageAndPosition> logMessageAndPositions = isKeyed
            ? writeThriftLogMessages(logger, processorBatchSize + 20, 500, 50)
            : writeThriftLogMessages(logger, processorBatchSize + 20, 500, 50);
        List<LogMessage> logMessages = getMessages(logMessageAndPositions);
        messagesWritten.addAll(logMessages);
      }

      // added to enable running this test on OS X
      System.err.println("Waiting for file system events to be noticed by FileSystemMonitor");
      while (logStream.isEmpty()) {
        Thread.sleep(1000);
        System.out.print(".");
      }

      Thread.sleep(100);
      // Process all message written so far.
      long numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should have read all of messages written(" + messagesWritten.size()
          + "), processed(" + numOfMessageProcessed + ")",
          messagesWritten.size(), numOfMessageProcessed);
      
      System.out.println(writer.getLogMessages().size() + " output vs " + messagesWritten.size() + " input");
      assertTrue("Should have only written 10% of the messages", 
          (messagesWritten.size() * (percentage - 5) / 100 >= writer.getLogMessages().size()) 
          || (messagesWritten.size() * (percentage + 5) / 100 >= writer.getLogMessages().size()));
    } catch (Exception e) {
      e.printStackTrace();
      fail("Got exception in test");
    } finally {
      logger.close();
      processor.close();
    }
  }

  public void testProcessLogStream(boolean isKeyed) throws Exception {
    String tempPath = getTempPath();
    String logStreamHeadFileName = "thrift.log";
    String path = FilenameUtils.concat(tempPath, logStreamHeadFileName);

    int oldestThriftLogIndex = 0;
    int readerBufferSize = 16000;
    int maxMessageSize = 16000;
    int processorBatchSize = 50;

    long processingIntervalInMillisMin = 1;
    long processingIntervalInMillisMax = 1;
    long processingTimeSliceInMilliseconds = 3600;
    int logRetentionInSecs = 15;

    // initialize a singer log config
    SingerLogConfig logConfig = new SingerLogConfig("test", tempPath, logStreamHeadFileName, null,
        null, null);
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
    NoOpLogStreamWriter writer = new NoOpLogStreamWriter();

    // initialize a log stream reader with 16K as readerBufferSize and
    // maxMessageSize
    LogStreamReader logStreamReader = new DefaultLogStreamReader(logStream,
        new ThriftLogFileReaderFactory(new ThriftReaderConfig(readerBufferSize, maxMessageSize)));
    // initialize a log stream processor that
    MemoryEfficientLogStreamProcessor processor = new MemoryEfficientLogStreamProcessor(logStream,
        null, logStreamReader, writer, processorBatchSize, processingIntervalInMillisMin,
        processingIntervalInMillisMax, processingTimeSliceInMilliseconds, logRetentionInSecs, false);

    try {
      // Write messages to be skipped.
      if (isKeyed)
        writeThriftLogMessages(logger, 150, 500, 50);
      else
        writeThriftLogMessages(logger, 150, 50);

      // Save start position to watermark file.
      LogPosition startPosition = new LogPosition(logger.getLogFile(), logger.getByteOffset());
      WatermarkUtils.saveCommittedPositionToWatermark(
          MemoryEfficientLogStreamProcessor.getWatermarkFilename(logStream), startPosition);

      List<LogMessage> messagesWritten = Lists.newArrayList();

      // Rotate log file while writing messages.
      for (int i = 0; i < 3; ++i) {
        rotateWithDelay(logger, 1000);
        List<LogMessageAndPosition> logMessageAndPositions = isKeyed
            ? writeThriftLogMessages(logger, processorBatchSize + 20, 500, 50)
            : writeThriftLogMessages(logger, processorBatchSize + 20, 500, 50);
        List<LogMessage> logMessages = getMessages(logMessageAndPositions);
        messagesWritten.addAll(logMessages);
      }

      // added to enable running this test on OS X
      System.err.println("Waiting for file system events to be noticed by FileSystemMonitor");
      while (logStream.isEmpty()) {
        Thread.sleep(1000);
        System.out.print(".");
      }

      // Process all message written so far.
      long numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should have processed all messages written", messagesWritten.size(),
          numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));

      // Write and process a single LogMessages.
      messagesWritten.addAll(getMessages(isKeyed ? writeThriftLogMessages(logger, 1, 500, 50)
          : writeThriftLogMessages(logger, 1, 50)));
      numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should have processed a single log message", 1, numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));

      // Write another set of LogMessages.
      messagesWritten.addAll(
          getMessages(isKeyed ? writeThriftLogMessages(logger, processorBatchSize + 1, 500, 50)
              : writeThriftLogMessages(logger, processorBatchSize + 1, 50)));

      // Writer will throw on write.
      writer.setThrowOnWrite(true);
      LogPosition positionBefore = WatermarkUtils.loadCommittedPositionFromWatermark(
          MemoryEfficientLogStreamProcessor.getWatermarkFilename(logStream));
      try {
        processor.processLogStream();
        fail("No exception is thrown on writer error");
      } catch (LogStreamProcessorException e) {
        // Exception is thrown.
      }
      LogPosition positionAfter = WatermarkUtils.loadCommittedPositionFromWatermark(
          MemoryEfficientLogStreamProcessor.getWatermarkFilename(logStream));
      assertEquals(positionBefore, positionAfter);

      // Write will not throw on write.
      writer.setThrowOnWrite(false);
      numOfMessageProcessed = processor.processLogStream();
      assertEquals("Should not have processed any additional messages", processorBatchSize + 1,
          numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));

      // Rotate and write twice before processing
      rotateWithDelay(logger, 1000);
      boolean successfullyAdded = messagesWritten.addAll(
          getMessages(isKeyed ? writeThriftLogMessages(logger, processorBatchSize - 20, 500, 50)
              : writeThriftLogMessages(logger, processorBatchSize - 20, 50)));
      assertTrue(successfullyAdded);
      rotateWithDelay(logger, 1000);
      successfullyAdded = messagesWritten
          .addAll(getMessages(isKeyed ? writeThriftLogMessages(logger, processorBatchSize, 500, 50)
              : writeThriftLogMessages(logger, processorBatchSize, 50)));
      assertTrue(successfullyAdded);

      // Need to wait for some time to make sure that messages have been written to
      // disk
      Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
      numOfMessageProcessed = processor.processLogStream();

      Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
      processor.processLogStream();

      assertEquals(2 * processorBatchSize - 20, numOfMessageProcessed);
      assertThat(writer.getLogMessages(), is(messagesWritten));
      processor.processLogStream();
      String oldThriftLogPath = FilenameUtils.concat(getTempPath(),
          "thrift.log." + oldestThriftLogIndex);
      File oldThriftLog = new File(oldThriftLogPath);
      assertFalse(oldThriftLog.exists()); // the oldest file is at least 10 seconds old now
      // oldThriftLogPath = FilenameUtils.concat(getTempPath(),
      // "thrift.log." + (oldestThriftLogIndex - 1));
      // oldThriftLog = new File(oldThriftLogPath);
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
  public void testProcessLogStreamWithDecider() throws Exception {
    MemoryEfficientLogStreamProcessor processor = null;
    try {
      SingerConfig singerConfig = new SingerConfig();
      singerConfig.setThreadPoolSize(1);
      singerConfig.setWriterThreadPoolSize(1);
      SingerSettings.initialize(singerConfig);
      SingerLog singerLog = new SingerLog(
          new SingerLogConfig("test", getTempPath(), "thrift.log", null, null, null));
      LogStream logStream = new LogStream(singerLog, "thrift.log");
      NoOpLogStreamWriter writer = new NoOpLogStreamWriter();
      processor = new MemoryEfficientLogStreamProcessor(logStream, "singer_test_decider",
          new DefaultLogStreamReader(logStream,
              new ThriftLogFileReaderFactory(new ThriftReaderConfig(16000, 16000))),
          writer, 50, 1, 1, 3600, 1800, false);
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
  public void testProcessLogStreamWithMessagesSkipped() throws Exception {
    String tempPath = getTempPath();
    String logStreamHeadFileName = "text.log";
    String path = FilenameUtils.concat(tempPath, logStreamHeadFileName);
    String infoMessage = "This is a sample INFO message\n";
    String errorMessage = "This is a sample ERROR message\n";

    int readerBufferSize = 16000;
    int maxMessageSize = 16000;
    int processorBatchSize = 200;

    long processingIntervalInMillisMin = 1;
    long processingIntervalInMillisMax = 1;
    long processingTimeSliceInMilliseconds = 3600;
    int logRetentionInSecs = 15;

    // initialize a singer log config
    SingerLogConfig
        logConfig =
        new SingerLogConfig("test", tempPath, logStreamHeadFileName, null, null, null);
    SingerLog singerLog = new SingerLog(logConfig);
    singerLog.getSingerLogConfig().setFilenameMatchMode(FileNameMatchMode.PREFIX);

    // initialize global variables in SingerSettings
    try {
      SingerConfig
          singerConfig =
          initializeSingerConfig(1, 1, Collections.singletonList(logConfig));
      SingerSettings.initialize(singerConfig);
    } catch (Exception e) {
      e.printStackTrace();
      fail("got exception in test: " + e);
    }

    // initialize log stream
    LogStream logStream = new LogStream(singerLog, logStreamHeadFileName);
    LogStreamManager.addLogStream(logStream);
    TextLogger textLogger = new TextLogger(path);

    // initialize reader, writer & processor
    NoOpLogStreamWriter writer = new NoOpLogStreamWriter();
    TextReaderConfig
        textReaderConfig =
        new TextReaderConfig(readerBufferSize, maxMessageSize, 1, "^.*$");
    // Regex to filter messages out
    textReaderConfig.setFilterMessageRegex(".*\\bERROR\\b.*");
    textReaderConfig.setTextLogMessageType(TextLogMessageType.PLAIN_TEXT);
    LogStreamReader logStreamReader = new DefaultLogStreamReader(
        logStream,
        new TextLogFileReaderFactory(textReaderConfig, null));
    MemoryEfficientLogStreamProcessor processor = new MemoryEfficientLogStreamProcessor(logStream,
        null, logStreamReader, writer, processorBatchSize, processingIntervalInMillisMin,
        processingIntervalInMillisMax, processingTimeSliceInMilliseconds, logRetentionInSecs, false);

    for (int i = 0; i < 100; ++i) {
      textLogger.logText(infoMessage);
      textLogger.logText(errorMessage);
    }

    // Save start position to watermark file.
    LogPosition
        startPosition =
        new LogPosition(new LogFile(SingerUtils.getFileInode(path)), 0);
    WatermarkUtils.saveCommittedPositionToWatermark(DefaultLogStreamProcessor
        .getWatermarkFilename(logStream), startPosition);

    // Process all message written so far.
    Thread.sleep(FILE_EVENT_WAIT_TIME_MS);
    long numOfMessageProcessed = processor.processLogStream();
    assertEquals("Should process all messages written ", processorBatchSize, numOfMessageProcessed);

    assertEquals("Should have processed only half of the messages written", 100, writer.getLogMessages().size());
    for (int i = 0; i < 100; i++) {
      assertEquals(errorMessage, new String(writer.getLogMessages().get(i).getMessage()));
    }
  }

  private static List<LogMessage> getMessages(List<LogMessageAndPosition> messageAndPositions) {
    List<LogMessage> messages = Lists.newArrayListWithExpectedSize(messageAndPositions.size());
    for (LogMessageAndPosition messageAndPosition : messageAndPositions) {
      messages.add(messageAndPosition.getLogMessage());
    }
    return messages;
  }
}