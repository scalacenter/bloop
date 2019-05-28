### BSP Debug Adapter Extension

### Debug Session  

A Debug Session is created every time the client asks for it. The client is responsible for terminating the session.
It starts being correctly set up with the required *launch parameters*. Those are used
to determine which task must be run (test suite, main class). 

The Debug Session converses with its Client using the Debug Adapter Protocol 
(https://microsoft.github.io/debug-adapter-protocol). 
Since the *launch parameters* are already known, it is unspecified how
the Server handles those sent using the DAP `launch` and `attach` request.

#### Start Debug session

This request is sent from the client to the server whenever it wants to start a new debug session. 
Client is responsible for terminating the session.

##### Request:
* method: `debugSession/start`
* params: `DebugSessionParams`

```scala
trait DebugSessionParams {
  def targets: List[BuildTargetIdentifier]
  def launchParameters: Any
}
```
- `launchParameters` - Server-specific structure used to instrument the debug session (i.e. `ScalaMainClass`). 
Those will be resolved in the context of `targets`.  
   

##### Response:

```scala
trait DebugSessionConnection {
  def uri: String
}
```

- `uri` - an address identifying the newly created debug session

##### Notifications:
Depending on the session type (test suite, main class), various notifications will be sent to the BSP Client (i.e. `TestReport`) 