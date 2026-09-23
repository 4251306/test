import express from "express";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import * as store from "./store.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

export function createApp() {
  const app = express();
  app.use(express.json());

  app.get("/api/health", (_req, res) => {
    res.json({ status: "ok", uptime: process.uptime() });
  });

  app.get("/api/tasks", (_req, res) => {
    res.json(store.list());
  });

  app.post("/api/tasks", (req, res, next) => {
    try {
      res.status(201).json(store.create({ title: req.body?.title }));
    } catch (err) {
      next(err);
    }
  });

  app.post("/api/tasks/:id/toggle", (req, res, next) => {
    try {
      res.json(store.toggle(req.params.id));
    } catch (err) {
      next(err);
    }
  });

  app.delete("/api/tasks/:id", (req, res, next) => {
    try {
      store.remove(req.params.id);
      res.status(204).end();
    } catch (err) {
      next(err);
    }
  });

  app.use(express.static(join(__dirname, "public")));

  app.use((err, _req, res, _next) => {
    res.status(err.status || 500).json({ error: err.message || "internal error" });
  });

  return app;
}
