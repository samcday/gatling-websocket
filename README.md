gatling-websocket
=================

This is a simple addon to add WebSocket support to
[Gatling](http://gatling-tool.org/).

Usage
=====

First create the jar:

    sbt package

... then copy `target/gatling-websocket-0.0.9.jar` to Gatling's `lib`
directory.

In your simulation, import the DSL:

    import com.giltgroupe.util.gatling.websocket.Predef._

You can now open a websocket:

    websocket("socket").open("ws://<url>/", "socket_open")

The parameter to `websocket` is the name of the session attribute used to
reference the socket later in the simulation. The second parameter to `open` is
optional and is the name used to record the amount of time the socket took to
open in the log; if omitted it defaults to the name of the session attribute.

You can specify extra headers to include in the initial HTTP request that sets
up the socket:

    websocket("socket").open("ws://<url>/", "socket_open").
      header("header", "value").
      headers(Map("header1" -> "value1", "header2" -> "value2"))

HTTP BASIC authentication is supported:

    websocket("socket").open("ws://<url>/", "socket_open").
      basicAuth("myUser", "myPassword")

You can send a text frame on an open socket:

     websocket("socket").sendMessage("test", "socket_send")

Again, the second parameter is optional and is the request name used to record
whether sending succeeded or not in the log (the time taken to send is usually
less than 1 millisecond).

Finally, you should close the socket at the end of the simulation:

    websocket("socket").close("socket_close")

The parameter is the optional request name again.

Complete example
================

    class WebsocketSimulation extends Simulation {
      val scn = scenario("Scenario name")
        .exec(websocket("socket").open("ws://localhost:8000/ws", "socket_open"))
        .exec(websocket("socket").sendMessage("my data", "socket_send"))
        .pause(10)
        .exec(websocket("socket").close("socket_close"))
    
      setUp(scn.users(10).ramp(2))
    }

License
=======

Copyright 2012-2013 Gilt Groupe, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
