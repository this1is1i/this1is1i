import sys
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_milestone7 as smoke


class CleanupTest(unittest.TestCase):
    def test_failed_resource_does_not_skip_other_services_or_proxy_close(self):
        first, second, proxy = Mock(), Mock(), Mock()
        second.stop.side_effect = RuntimeError('stop failed')
        proxy.shutdown.side_effect = RuntimeError('proxy failed')
        with self.assertRaises(RuntimeError):
            smoke.stop_resources([first, second], [proxy])
        first.stop.assert_called_once()
        second.stop.assert_called_once()
        proxy.server_close.assert_called_once()

    def test_failed_graceful_shutdown_stops_owned_process_and_closes_log(self):
        server = smoke.Server.__new__(smoke.Server)
        server.child, server.stream, server.origin = Mock(), Mock(), 'http://127.0.0.1:1'
        server.child.poll.return_value = None
        with patch.object(smoke, 'request', side_effect=RuntimeError('unreachable')):
            with self.assertRaisesRegex(RuntimeError, 'unreachable'):
                server.stop()
        server.child.terminate.assert_called_once()
        server.child.wait.assert_called_once_with(timeout=20)
        server.stream.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
