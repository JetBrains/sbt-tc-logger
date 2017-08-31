package com.jetbrains.teamcity.specs.some_name.some_group

import org.scalatest._
import scala.sys.process._

class MyTestModeSpec extends FeatureSpec{

  info("Info Goes Here")

  feature("Some Advanced Mode") {

    scenario("XYZ-1: Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua", Tag("@negative")) {

    }

    scenario("XYZ-2: Lorem Ipsum is simply dummy text of the printing and typesetting industry.", Tag("@negative")) {

    }

  }

}

