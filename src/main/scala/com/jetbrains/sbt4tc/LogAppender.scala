package com.jetbrains.sbt4tc
import sbt._

trait LogAppender {

  def log(level: sbt.Level.Value, message: => String, flowId: Long)

  def compilationBlockStart(flowId: Long)

  def compilationBlockEnd(flowId: Long)

  def testSuitStart(name: String, flowId: Long)

  def testSuitSuccessfulResult(name: String, flowId: Long)
  
  def testSuitFailResult(name: String, t: Throwable, flowId: Long)
}