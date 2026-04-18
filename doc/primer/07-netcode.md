# Client-Server Netcode

## High-Level Flow

This client uses two different network phases:

1. login/bootstrap over TLS/TCP
2. live game session over UDP with a custom reliability layer

Core files:

- `src/haven/Bootstrap.java`
- `src/haven/AuthClient.java`
- `src/haven/Session.java`
- `src/haven/Connection.java`
- `src/haven/Message.java`
- `src/haven/PMessage.java`
- `src/haven/RMessage.java`
- `src/haven/RemoteUI.java`

## Bootstrap

`Bootstrap` and `AuthClient` authenticate the user, fetch session information, and create a `Session`.

## Live Session

`Session` is the bridge between raw protocol traffic and higher-level game state.

It owns:

- `Glob` for live world state
- the resource-id cache
- the queued UI message stream consumed by `RemoteUI`

`Connection` handles the lower transport details, including:

- UDP packet IO
- reliable submessages
- acknowledgements and retransmission
- object and map update packets
- optional packet encryption

## Where Updates Go

Different message classes feed different subsystems:

- global state updates -> `Glob.blob(...)`
- map data -> `MCache.mapdata(...)`
- object deltas -> `OCache.receive(...)`
- UI creation and widget messages -> `RemoteUI` and `UI`

That split is one of the most important architectural facts in the client.

## Native Client Behavior

Native Haven gives you the whole protocol backbone:

- login/auth flow
- UDP session transport
- `Message`, `PMessage`, and `RMessage`
- resource-id resolution in `Session`
- server-driven UI construction through `RemoteUI`
- state application into `Glob`, `MCache`, and `OCache`

## Ender Additions

This fork mostly consumes decoded state rather than replacing the protocol itself, but it does add hooks around it:

- extra user-agent metadata from `RemoteUI.sendua(...)`
- config-driven gob and map refresh hooks in `OCache` and `MCache`
- mapper integration that listens to movement and map state
- timer and window-related reactive consumers layered on top of session-fed state

So the wire protocol is mostly native, while the fork adds more observers and derived behaviors on top of the already-decoded data.

## Gotchas

- The live session is UDP-based, not TCP-based.
- Reliability is implemented inside the client protocol, not by the transport alone.
- UI messages are queued and replayed through `RemoteUI`, not directly applied from the network thread.
- Resource resolution is asynchronous, so code that dereferences resources must tolerate `Loading`.
