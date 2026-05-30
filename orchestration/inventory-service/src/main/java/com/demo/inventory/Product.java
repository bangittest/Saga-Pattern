package com.demo.inventory;

import jakarta.persistence.*;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private String id;   // e.g. "P1"
    private int stock;

    protected Product() {}

    public Product(String id, int stock) {
        this.id = id;
        this.stock = stock;
    }

    public String getId() { return id; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
}