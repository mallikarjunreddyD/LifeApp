"""Send test-only credentials to the owner's interactive container command."""
import os
import pty
import select
import subprocess
import sys
import time

master, terminal = pty.openpty()
with subprocess.Popen(sys.argv[1:], stdin=terminal, stdout=terminal, stderr=terminal) as process:
    os.close(terminal)
    try:
        for prompt, answer in (
            (b"Owner username: ", b"compose-smoke-owner\n"),
            (b"Owner password: ", b"disposable compose smoke password\n"),
            (b"Owner initialized.", None),
        ):
            output = bytearray()
            deadline = time.monotonic() + 25
            while prompt not in output:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not select.select([master], [], [], remaining)[0]:
                    raise RuntimeError(f"Owner command did not show {prompt.decode().strip()}")
                try:
                    output.extend(os.read(master, 4096))
                except OSError as error:
                    raise RuntimeError(f"Owner command ended before {prompt.decode().strip()}: {output.decode(errors='replace')}") from error
            if answer:
                os.write(master, answer)
        if process.wait(timeout=10) != 0:
            raise RuntimeError("Owner command failed")
    finally:
        os.close(master)
        if process.poll() is None:
            process.kill()
