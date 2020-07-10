import Dependencies._

name := "distage-test-project"

version := "0.1"

scalaVersion := "2.13.3"

libraryDependencies ++= pallas ++ sttp ++ catsEffect ++ distage ++ zio

resolvers += "jitpack" at "https://jitpack.io"