# Stock Exchange back-end

A QuarkusIO application serving as the Stock Exchange back-end in the FIX Trading Simulator. It accepts FIX connections from brokers, maintains an order book, and provides a REST API and WebSocket updates for the Angular front-end.

## Technologies

- Quarkus 1.13.3
- QuickFIX/J 2.1.0
- PostgreSQL 13
- RESTEasy + JSON-B
- WebSockets
- Hibernate ORM

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```bash
./mvnw compile quarkus:dev
```

## Packaging and running the application

The application can be packaged using:

```bash
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it's not an über-jar as the dependencies are copied into the `target/quarkus-app/lib/` directory.

If you want to build an über-jar, execute:

```bash
./mvnw package -DskipTests -Dquarkus.package.type=uber-jar
```

The application, packaged as an über-jar, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```bash
./mvnw package -DskipTests -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```bash
./mvnw package -DskipTests -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/exchange-back-end-1.0.0-SNAPSHOT-runner`

## REST API

The application provides a Swagger UI at: `http://localhost:8090/q/swagger-ui/`

Main endpoints:
- `GET /orders` - List all orders
- `GET /orders/{orderID}` - Get specific order
- `PATCH /orders/{orderID}` - Edit order
- `DELETE /orders/{orderID}` - Cancel order
- `GET /negotiation` - Get negotiation status
- `PUT /negotiation` - Update negotiation settings
- `GET /session` - Get session information
- `GET /logs/event` - Get event logs
- `GET /logs/messages-incoming` - Get incoming messages
- `GET /logs/messages-outgoing` - Get outgoing messages

## WebSocket

Real-time order updates are available via WebSocket at: `ws://localhost:8090/socket/order`

## Configuration

Application configuration is in `application.yml` and `application.properties`.

Key properties:
- `quickfix.activateScreenLog` - Enable screen logging
- `quickfix.automatic.trade` - Enable automatic trade execution
- `quickfix.automatic.trade.seconds` - Delay between automatic trades
