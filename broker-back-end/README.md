# Broker back-end

A QuarkusIO application serving as the Broker back-end in the FIX Trading Simulator. It connects to the Exchange using FIX protocol, manages orders on behalf of clients, and provides a REST API and WebSocket updates for the Angular front-end.

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

## REST API

The application provides a Swagger UI at: `http://localhost:8080/q/swagger-ui/`

Main endpoints:
- `GET /orders` - List all orders
- `POST /orders` - Create new order
- `GET /orders/{clOrdID}` - Get specific order
- `DELETE /orders/{clOrdID}` - Cancel order
- `GET /session` - Get session information
- `POST /session/logon` - Logon to exchange
- `POST /session/logout` - Logout from exchange
- `GET /logs/event` - Get event logs
- `GET /logs/messages-incoming` - Get incoming messages
- `GET /logs/messages-outgoing` - Get outgoing messages

## WebSocket

Real-time order updates are available via WebSocket at: `ws://localhost:8080/socket/order`
