import { startServer } from "./server.js";
import { loadConfig } from "./config.js";

startServer(loadConfig());
