package com.jetbrains.sbt.test;

public class HelloWorld{

    public void sayHello(){
        System.out.println("Hello, World!");
        System.out.println(System.getProperty("java.version"));
        System.out.println(System.getProperty("java.home"));
    }

    public static void main(String[] args) {
        new HelloWorld().sayHello();
    }
}