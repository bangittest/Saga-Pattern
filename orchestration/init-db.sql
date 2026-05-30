-- Each microservice gets its OWN logical database. Services never share schemas;
-- this is the "database per service" principle. In production these would often be
-- separate Postgres instances entirely — here we keep one container for a light demo.
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;
CREATE DATABASE inventorydb;