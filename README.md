# Korolev

<img src="https://fomkin.org/korolev/korolev-face-margin.svg" align="right" width="260" />

[![Join the chat at https://telegram.me/korolev_io](https://img.shields.io/badge/chat-on_telegram_(russian)-0088cc.svg)](https://telegram.me/korolev_io)

Not long ago we have entered the era of single-page applications. Some people say that we no longer need a server. They say that JavaScript applications can connect to DBMS directly. Fat clients. **We disagree with this.** This project is an attempt to solve the problems of modern fat web.

Korolev runs a single-page application on the server side, keeping in the browser only a bridge to receive commands and send events. The page loads instantly and works fast, because it does a minimal amount of computation. It's important that Korolev provides a unified environment for full stack development. Client and server are now combined into a single app without any REST protocol or something else in the middle.

## Why?

* Lightning-fast page loading speed (~6kB of uncompressed JS)
* Comparable to static HTML client-side RAM consumption
* Indexable pages out of the box
* Routing out of the box
* Build extremely large app without increasing size of the page
* No need to make CRUD REST service
* Connect to infrastructure (DBMS, Message queue) directly from application

## Examples

* [Features](https://github.com/fomkin/korolev/tree/master/examples)
* [Multiplayer match-three game build on Korolev](https://match3.fomkin.org/)
 
## Documentation

* [User guide (open site)](https://fomkin.org/korolev/user-guide.html), [(download PDF)](https://fomkin.org/korolev/user-guide.pdf)
* [API overview](https://www.javadoc.io/doc/org.fomkin/korolev_2.13/1.1.0) 

## Articles

* [Slimming pill for Web](https://dev.to/fomkin/korolev-slimming-pill-for-web-549a)
* [Лекарство для веба](https://habr.com/ru/post/429028/)

## Tools

* [HTML to Levsha DSL converter](https://fomkin.org/korolev/html-to-levsha)
