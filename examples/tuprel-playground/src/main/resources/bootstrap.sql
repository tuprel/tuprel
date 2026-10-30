-- Temporary, manually maintained DDL. This is NOT a Tuprel migration.
-- The application confines this script to tuprel_devtest.tuprel_playground.
CREATE TABLE IF NOT EXISTS "Customer" (
    "id" UUID PRIMARY KEY,
    "email" TEXT NOT NULL UNIQUE,
    "name" TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS "Product" (
    "id" UUID PRIMARY KEY,
    "name" TEXT NOT NULL,
    "price" NUMERIC(12,2) NOT NULL CHECK ("price" >= 0),
    "stock" INTEGER NOT NULL CHECK ("stock" >= 0),
    "version" INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS "SalesOrder" (
    "id" UUID PRIMARY KEY,
    "customerId" UUID NOT NULL REFERENCES "Customer" ("id"),
    "status" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS "OrderItem" (
    "id" UUID PRIMARY KEY,
    "orderId" UUID NOT NULL REFERENCES "SalesOrder" ("id"),
    "productId" UUID NOT NULL REFERENCES "Product" ("id"),
    "quantity" INTEGER NOT NULL CHECK ("quantity" > 0),
    "unitPrice" NUMERIC(12,2) NOT NULL CHECK ("unitPrice" >= 0),
    UNIQUE ("orderId", "productId")
);
