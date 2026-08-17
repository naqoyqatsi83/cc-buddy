#!/usr/bin/env node
import { Command } from "commander";
import { createServer } from "node:net";
import { startControlApi, type SessionRef } from "./controlApi.js";
import { startSession } from "./session.js";
import { sessionRegistry } from "./sessionRegistry.js";
import { installHooks, uninstallHooks } from "./hooksConfig.js";

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
    const sessionRef: SessionRef = {};
    await startControlApi(controlPort, sessionRef);

    // Must happen before the PTY spawns claude — hooks are only read at
    // startup, not live-reloaded.
    installHooks(opts.cwd, controlPort);

    // Whatever way this process ends -- the wrapped claude exiting normally
    // (session.ts calls process.exit() on that), Ctrl+C, or a kill -- take
    // the now-dead hooks back out so they don't keep firing ECONNREFUSED
    // until the next `buddy start` overwrites them.
    let hooksRemoved = false;
    const cleanupHooks = () => {
      if (hooksRemoved) return;
      hooksRemoved = true;
      uninstallHooks(opts.cwd);
    };
    process.on("exit", cleanupHooks);
    process.on("SIGINT", () => {
      cleanupHooks();
      process.exit(130);
    });
    process.on("SIGTERM", () => {
      cleanupHooks();
      process.exit(143);
    });

    const session = startSession({
      cwd: opts.cwd,
      controlPort,
      claudeArgs: cmd.args, // anything after `start --cwd ...` is forwarded to claude verbatim
    });

    sessionRef.current = session;
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
