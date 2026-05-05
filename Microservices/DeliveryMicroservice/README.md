# Delivery Service

Handles all logistics, assignment, and delivery tracking.

## Features
- Manages delivery orders, partial delivery logic, and item quantities.
- Synchronizes delivery status and stock picking data back to Odoo via RPC.
- Communicates with RabbitMQ for event-driven async messaging.
- Port: 8082.
- Built with Spring Boot and PostgreSQL.
