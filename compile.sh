#!/bin/bash
cd $( dirname "$0" )
if [ -f ./get-jackson.sh ]
then
  ./get-jackson.sh
fi
if [ -f ./get-jfreechart.sh ]
then
  ./get-jfreechart.sh
fi

cp=""
first="1"
for k in jar/* jfreechart-1.0.13/lib/*.jar; do
  if [ first == "1" ]; then first=0; else cp="${cp}:"; fi
  cp="${cp}${k}"
done

rm -r bin/*
if [ -f src/spark/timeseries/j/PlotData.java ]; then
  javac -d bin -cp "${cp}" src/spark/timeseries/j/PlotData.java
fi

#scalac -deprecation -unchecked -d bin -cp "${cp}:bin" $( find src -type f -name "*.scala" ) $*
scalac -d bin -cp "${cp}:bin" $( find src -type f -name "*.scala" ) $*

