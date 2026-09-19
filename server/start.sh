#!/bin/sh
set -a; . ./relay.env; set +a
exec java -Xms128M -Xmx3g -jar server.jar
