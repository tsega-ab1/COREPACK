import os
import sys
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from corepack import client, tasks, worker  # noqa: E402
from corepack.pool import Pool  # noqa: E402
from corepack.scheduler import Node, run_job  # noqa: E402


class TaskTests(unittest.TestCase):
    def test_primes_known_value(self):
        self.assertEqual(tasks.execute("primes", {"lo": 0, "hi": 100}), 25)

    def test_jobs_cover_whole_range(self):
        chunks = tasks.build_job("primes", "small")
        self.assertEqual(chunks[0]["lo"], 0)
        self.assertEqual(chunks[-1]["hi"], 400_000 * tasks.SIZES["small"])
        for a, b in zip(chunks, chunks[1:]):
            self.assertEqual(a["hi"], b["lo"])

    def test_unknown_task_rejected(self):
        with self.assertRaises(ValueError):
            tasks.execute("rm -rf", {})


class SchedulerTests(unittest.TestCase):
    def test_same_result_regardless_of_node_mix(self):
        chunks = [{"lo": i * 100, "hi": (i + 1) * 100} for i in range(20)]
        one = run_job("primes", chunks, [Node("a", 1, tasks.execute)])[0]
        two = run_job("primes", chunks, [Node("a", 2, tasks.execute), Node("b", 3, tasks.execute)])[0]
        self.assertEqual(one, two)

    def test_failed_node_does_not_lose_work(self):
        def broken(task, args):
            raise RuntimeError("cable pulled")
        chunks = [{"lo": i * 100, "hi": (i + 1) * 100} for i in range(10)]
        import time

        def slow(task, args):
            time.sleep(0.02)  # give the broken node a chance to grab chunks first
            return tasks.execute(task, args)
        good = Node("good", 1, slow)
        bad = Node("bad", 2, broken)
        res, _ = run_job("primes", chunks, [good, bad])
        self.assertEqual(sum(res), tasks.execute("primes", {"lo": 0, "hi": 1000}))
        self.assertTrue(bad.failed)


class WorkerTests(unittest.TestCase):
    def test_loopback_worker(self):
        pool = Pool(2, force_threads=True)
        srv = worker.make_server("127.0.0.1", 0, pool, token="s3")
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        port = srv.server_address[1]
        try:
            p = client.Peer("127.0.0.1", port, "s3")
            self.assertEqual(p.info()["service"], "corepack")
            self.assertEqual(p.run("primes", {"lo": 0, "hi": 100}), 25)
            with self.assertRaises(client.PeerError):
                p.run("nope", {})
            with self.assertRaises(client.PeerError):
                client.Peer("127.0.0.1", port, "wrong").info()
            self.assertGreater(p.upload(2), 0)
            self.assertGreater(p.download(2), 0)
        finally:
            srv.shutdown()
            srv.server_close()
            pool.close()


if __name__ == "__main__":
    unittest.main()
