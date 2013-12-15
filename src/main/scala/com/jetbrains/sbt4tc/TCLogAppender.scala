package com.jetbrains.sbt4tc

import sbt._
import jetbrains.buildServer._
import jetbrains.buildServer.messages.serviceMessages

class TCLogAppender extends LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: Long) = {
    val status = discoverStatus(level)
    val escaped = jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil.escapeStr(message, jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil.STD_ESCAPER2)
    println(s"##teamcity[message status='$status' flowId='$flowId' text='$escaped']")

  }

  def discoverStatus(level: sbt.Level.Value): String = {
    val status = level match {
      case Level.Error => "ERROR"
      case Level.Warn => "WARNING"
      case other => "NORMAL"
    }
    status
  }

  def compilationBlockStart(flowId: Long) {
    println(s"##teamcity[compilationStarted compiler='Scala' flowId='$flowId']")
  }

  def compilationBlockEnd(flowId: Long) {
    println(s"##teamcity[compilationFinished compiler='Scala' flowId='$flowId']")
  }


  def testSuitStart(name: String, flowId: Long) {
    println(s"##teamcity[testSuiteStarted name='$name' flowId='$flowId']")
  }

  def testSuitSuccessfulResult(name: String, flowId: Long){
    println(s"##teamcity[testFinished name='$name' flowId='$flowId']")
  }

  def testSuitFailResult(name: String, t: Throwable, flowId: Long) {
    val message = t.getMessage
    val details = t.getStackTrace
    println(s"##teamcity[testFailed name='$name' message='$message' details='$details' fowId='$flowId']")
  }
}
