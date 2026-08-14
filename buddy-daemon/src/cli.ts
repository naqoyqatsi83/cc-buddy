#!/usr/bin/env node
import { Command } from "commander";
import { createServer } from "node:net";
import { startControlApi } from "./controlApi.js";
import { startSession } from "./session.js";
import { sessionRegistry } from "./sessionRegistry.js";

const program = new Command();

program
  .name("buddy")
  .description("Wraps `claude` so it can be paired with the CC Buddy app")
  .enablePositionalOptions();

program
  .command("start")
  .description("Start claude inside buddy-daemon (drop-in replacement for running `claude` directly)")
  .option("--cwd <path>", "working directory to launch claude in", process.cwd())
  .passThroughOptions()
  .allowUnknownOption(true)
  .action(async (opts, cmd) => {
    const controlPort = await findFreePort();
    await startControlApi(controlPort);

    const session = startSession({
      cwd: opts.cwd,
      controlPort,
      claudeArgs: cmd.args, // anything after `start --cwd ...` is forwarded to claude verbatim
    });

    sessionRegistry.add(session);
  });

program.parseAsync(process.argv);

function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.listen(0, "127.0.0.1", () => {
      const address = srv.address();
      if (address && typeof address === "object") {
        const port = address.port;
        srv.close(() => resolve(port));
      } else {
        srv.close(() => reject(new Error("could not allocate control port")));
      }
    });
  });
}
