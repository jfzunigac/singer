package com.pinterest.singer.transforms;

import com.pinterest.singer.SingerTestBase;
import com.pinterest.singer.common.LogStream;
import com.pinterest.singer.common.SingerLog;
import com.pinterest.singer.thrift.configuration.MessageTransformerConfig;
import com.pinterest.singer.thrift.configuration.RegexBasedModifierConfig;
import com.pinterest.singer.thrift.configuration.SingerLogConfig;
import com.pinterest.singer.thrift.configuration.TransformType;

import com.google.common.base.Charsets;
import org.junit.Test;

import java.nio.ByteBuffer;

public class RegexBasedModifierTest extends SingerTestBase {

  MessageTransformerConfig messageTransformerConfig;
  RegexBasedModifierConfig regexBasedModifierConfig;
  LogStream logStream;

  @Override
  public void setUp() {
    messageTransformerConfig = new MessageTransformerConfig();
    messageTransformerConfig.setType(TransformType.REGEX_BASED_MODIFIER);
    regexBasedModifierConfig = new RegexBasedModifierConfig();
    SingerLog singerLog = new SingerLog(
        new SingerLogConfig("test", "test_dir", "test.log", null, null, null));
    logStream = new LogStream(singerLog, "test.log");
  }

  @Override
  public void tearDown() {
    messageTransformerConfig = null;
    regexBasedModifierConfig = null;
  }

  @Test
  public void testSimpleLogExtraction() throws Exception {
    regexBasedModifierConfig.setRegex("\\[(?<thread>.*?)\\] (?<message>.*)");
    regexBasedModifierConfig.setModifiedMessageFormat("{Thread: \"$1\", Log: \"$2\"}");
    regexBasedModifierConfig.setEncoding("UTF-8");
    regexBasedModifierConfig.setAppendNewLine(false);
    messageTransformerConfig.setRegexBasedModifierConfig(regexBasedModifierConfig);
    RegexBasedModifier regexBasedModifier = new RegexBasedModifier(regexBasedModifierConfig, logStream);

    String logMessage = "[SingerMain] Starting Singer...";
    ByteBuffer logMessageBuf = regexBasedModifier.transform(ByteBuffer.wrap(logMessage.getBytes()));

    String expected = "{Thread: \"SingerMain\", Log: \"Starting Singer...\"}";
    assertEquals(expected, new String(logMessageBuf.array(), 0, logMessageBuf.limit(), Charsets.UTF_8));
  }

  @Test
  public void testNoRegexMatch() throws Exception {
    regexBasedModifierConfig.setRegex("singer_(\\d+).log");
    regexBasedModifierConfig.setModifiedMessageFormat("singer log number: $1");
    regexBasedModifierConfig.setEncoding("UTF-8");
    messageTransformerConfig.setRegexBasedModifierConfig(regexBasedModifierConfig);
    RegexBasedModifier regexBasedModifier = new RegexBasedModifier(regexBasedModifierConfig, logStream);

    String logMessage = "This is a sample message";
    ByteBuffer logMessageBuf = ByteBuffer.wrap(logMessage.getBytes());
    assertEquals(logMessageBuf, regexBasedModifier.transform(logMessageBuf));
  }

}
