import copy
import importlib.util
import json
import io
from unittest.mock import patch
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('probe', Path(__file__).parents[1] / 'plex_sharing_probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def setup():
    return {'baseUrl': 'https://plex.invalid', 'serverId': 'server-id', 'musicLibraryId': '7',
            'configurationLibraryId': '9', 'itemId': '42', 'dummyRecord': probe.dummy_record('server-id', '7')}


def metadata(config):
    return {'MediaContainer': {'Metadata': [{'ratingKey': '42', 'type': 'album', 'librarySectionID': '9',
            'summary': json.dumps(config['dummyRecord']), 'Field': [{'name': 'summary', 'locked': True}]}]}}


class FakeReader:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []

    def get(self, url, token):
        self.calls.append((url, token))
        return next(self.responses)


class ProbeTest(unittest.TestCase):
    def test_progress_reports_only_fixed_messages(self):
        entry = {'accountControlValid': True, 'directRead': {'status': 'denied_403'}}
        self.assertIn('denied', probe.sample_progress(entry))
        entry['accountControlValid'] = False
        self.assertIn('inconclusive', probe.sample_progress(entry))
        entry = {'accountControlValid': True,
                 'directRead': {'status': 'secret-token', 'result': 'secret-token'}}
        self.assertNotIn('secret-token', probe.sample_progress(entry))
        entry['directRead'] = {'status': 'ok', 'result': 'dummy_matches'}
        self.assertIn('matches', probe.sample_progress(entry))

    def test_browser_login_checks_identity_without_printing_token(self):
        reader = FakeReader([('ok', {'authToken': None}), ('ok', {'authToken': 'secret-token'}),
                             ('ok', {'username': 'mjst23'})])
        reader.request = lambda *a, **k: ('ok', {'id': 42, 'code': 'ABCD'})
        output = []
        token = probe.browser_login(reader, 'mjst23', emit=output.append, sleep=lambda _: None)
        self.assertEqual(token, 'secret-token')
        self.assertNotIn(token, ' '.join(output))
        self.assertTrue(all(url.startswith('https://plex.tv/') for url, _ in reader.calls))

    def test_browser_login_rejects_wrong_account_and_malformed_pin(self):
        reader = FakeReader([('ok', {'authToken': 'secret-token'}), ('ok', {'username': 'owner'})])
        reader.request = lambda *a, **k: ('ok', {'id': 42, 'code': 'ABCD'})
        with self.assertRaises(ValueError):
            probe.browser_login(reader, 'mjst23', emit=lambda _: None)
        reader.request = lambda *a, **k: ('ok', {'id': '../user', 'code': 'ABCD'})
        with self.assertRaises(ValueError):
            probe.browser_login(reader, 'mjst23', emit=lambda _: None)

    def test_browser_login_times_out_without_a_token(self):
        reader = FakeReader([])
        reader.request = lambda *a, **k: ('ok', {'id': 42, 'code': 'ABCD'})
        times = iter([0, 301])
        with self.assertRaises(ValueError):
            probe.browser_login(reader, 'mjst23', emit=lambda _: None, clock=lambda: next(times))

    def test_rejects_real_or_enabled_credentials_and_same_library(self):
        valid = setup()
        probe.validate_setup(valid)
        for key, value in [('password', 'real-secret'), ('url', 'https://real-grabber.example')]:
            bad = copy.deepcopy(valid)
            bad['dummyRecord']['musicGrabber'][key] = value
            with self.assertRaises(ValueError):
                probe.validate_setup(bad)
        bad = copy.deepcopy(valid)
        bad['dummyRecord']['version'] = True
        with self.assertRaises(ValueError):
            probe.validate_setup(bad)
        for flag in ('allowAcquisition', 'allowRoomAcquisition'):
            bad = copy.deepcopy(valid)
            bad['dummyRecord'][flag] = True
            with self.assertRaises(ValueError):
                probe.validate_setup(bad)
        bad = copy.deepcopy(valid)
        bad['configurationLibraryId'] = '7'
        with self.assertRaises(ValueError):
            probe.validate_setup(bad)

    def test_urls_reject_credential_and_redirect_destinations(self):
        self.assertEqual('https://plex.invalid', probe.base_url('https://plex.invalid/'))
        self.assertEqual('http://127.0.0.1:32400', probe.base_url('http://127.0.0.1:32400', True))
        for url in ('http://plex.invalid', 'http://8.8.8.8', 'https://u:p@plex.invalid',
                    'https://plex.invalid/path', 'https://plex.invalid?X-Plex-Token=secret',
                    'https://plex.invalid/#secret', 'https://plex.invalid\n', 'file:///tmp/config'):
            with self.assertRaises(ValueError, msg=url):
                probe.base_url(url, True)
        for key in ('../42', '1?x=2', '', '7/refresh'):
            with self.assertRaises(ValueError):
                probe.number(key)

    def test_item_binding_summary_lock_and_ambiguity(self):
        config = setup()
        value = metadata(config)
        self.assertEqual('dummy_matches', probe.inspect_item(value, config)['result'])
        self.assertTrue(probe.inspect_item(value, config)['summaryLockReported'])
        for key, replacement in [('ratingKey', '99'), ('type', 'playlist'), ('librarySectionID', '7')]:
            forged = copy.deepcopy(value)
            forged['MediaContainer']['Metadata'][0][key] = replacement
            self.assertEqual('record_or_binding_mismatch', probe.inspect_item(forged, config)['result'])
        value['MediaContainer']['Metadata'] *= 2
        self.assertEqual('ambiguous_or_missing_metadata', probe.inspect_item(value, config)['result'])

    def test_oversize_duplicate_and_malformed_summary_are_rejected(self):
        config = setup()
        for text in ('x' * (probe.MAX_RECORD + 1), '{', '{"version":1,"version":2}'):
            value = metadata(config)
            value['MediaContainer']['Metadata'][0]['summary'] = text
            self.assertNotEqual('dummy_matches', probe.inspect_item(value, config)['result'])
        with self.assertRaises(ValueError):
            probe.strict_json('{"x":1,"x":1}')
        with self.assertRaises(ValueError):
            probe.strict_json('{"x":NaN}')

    def test_discovery_pages_and_duplicate_candidates_are_explicit(self):
        config = setup()
        first = metadata(config)['MediaContainer']['Metadata']
        first += [{'ratingKey': str(i), 'summary': ''} for i in range(100, 149)]
        second = copy.deepcopy(metadata(config)['MediaContainer']['Metadata'])
        second[0]['ratingKey'] = '43'
        reader = FakeReader([('ok', {'MediaContainer': {'Metadata': first, 'totalSize': 51}}),
                             ('ok', {'MediaContainer': {'Metadata': second, 'totalSize': 51}})])
        result = probe.library_discovery(reader, 'shared-secret', config)
        self.assertTrue(result['complete'])
        self.assertTrue(result['targetDiscovered'])
        self.assertEqual(2, result['configurationCandidates'])
        self.assertIn('X-Plex-Container-Start=50', reader.calls[-1][0])

    def test_truncation_repeated_pages_and_denials_never_claim_complete_discovery(self):
        config = setup()
        page = ('ok', {'MediaContainer': {'Metadata': [{'ratingKey': str(i)} for i in range(50)], 'totalSize': 100}})
        reader = FakeReader([page, page])
        self.assertEqual('repeated_page', probe.library_discovery(reader, 'secret', config)['status'])
        reversed_page = ('ok', {'MediaContainer': {'Metadata': list(reversed(page[1]['MediaContainer']['Metadata'])), 'totalSize': 100}})
        self.assertFalse(probe.library_discovery(FakeReader([page, reversed_page]), 'secret', config)['complete'])
        for response in [('ok', {'MediaContainer': {'Metadata': [{'ratingKey': '42'}], 'size': 50}}), ('denied_403', None), ('network_error', None), ('ok', {'MediaContainer': {}}),
                         ('ok', {'MediaContainer': {'Metadata': [], 'totalSize': 50}})]:
            self.assertFalse(probe.library_discovery(FakeReader([response]), 'secret', config)['complete'])

    def test_token_selection_never_borrows_another_servers_token(self):
        config = setup()
        resources = [{'clientIdentifier': 'other', 'provides': 'server', 'accessToken': 'owner-secret', 'owned': True},
                     {'clientIdentifier': 'server-id', 'provides': 'server', 'accessToken': 'shared-secret', 'owned': False}]
        token, evidence = probe.select_token(FakeReader([('ok', resources)]), 'account-secret', config, 'resource')
        self.assertEqual('shared-secret', token)
        self.assertFalse(evidence['owned'])
        token, evidence = probe.select_token(FakeReader([('ok', resources + [resources[1]])]), 'account-secret', config, 'resource')
        self.assertEqual('account-secret', token)
        self.assertEqual(2, evidence['resourceMatches'])
        self.assertNotIn('secret', json.dumps(evidence))

    def test_denied_direct_read_requires_valid_account_control_and_never_leaks_raw_data(self):
        config = setup()
        reader = FakeReader([('ok', {'id': '123', 'username': 'private-name', 'email': 'private@example.com'}),
                             ('denied_403', None), ('denied_403', None), ('denied_403', None)])
        evidence = probe.sample(reader, 'account-secret', 'server-secret', config, 'item')
        self.assertTrue(evidence['accountControlValid'])
        self.assertEqual('denied_403', evidence['directRead']['status'])
        self.assertFalse(evidence['discovery']['complete'])
        report = json.dumps(evidence)
        for secret in ('account-secret', 'server-secret', 'private-name', 'private@example.com', 'plex.invalid'):
            self.assertNotIn(secret, report)
        reader = FakeReader([('denied_401', None)] * 4)
        self.assertFalse(probe.sample(reader, 'expired', 'expired', config, 'item')['accountControlValid'])

    def test_mistaken_token_argument_is_not_echoed_in_cli_errors(self):
        with patch.object(probe.sys, 'argv', ['probe', '--token=ACCIDENTAL_SECRET']), patch('sys.stderr', new_callable=io.StringIO) as error:
            with self.assertRaises(SystemExit):
                probe.main()
        self.assertNotIn('ACCIDENTAL_SECRET', error.getvalue())

    def test_evidence_write_refuses_overwrite_and_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'report.json'
            probe.write_json(path, {'status': 'test'})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                probe.write_json(path, {})
            link = Path(temp) / 'link.json'
            link.symlink_to(Path(temp) / 'missing')
            with self.assertRaises(FileExistsError):
                probe.write_json(link, {})

    def test_full_cli_run_writes_sanitized_report_without_saving_prompted_token(self):
        config = setup()
        reader = FakeReader([
            ('ok', {'id': 123, 'username': 'private-name'}),
            ('ok', [{'clientIdentifier': 'server-id', 'provides': 'server', 'owned': False, 'accessToken': 'RESOURCE_SECRET'}]),
            ('ok', {'id': 123}), ('ok', {'MediaContainer': {'machineIdentifier': 'server-id'}}),
            ('ok', metadata(config)), ('ok', {'MediaContainer': {'Directory': [{'key': '7', 'type': 'artist'}]}}),
        ])
        with tempfile.TemporaryDirectory() as temp:
            setup_path = Path(temp) / 'setup.json'
            report_path = Path(temp) / 'report.json'
            probe.write_json(setup_path, config)
            args = ['probe', 'run', '--setup', str(setup_path), '--role', 'approved', '--phase', 'shared', '--output', str(report_path)]
            with patch.object(probe.sys, 'argv', args), patch.object(probe.sys.stdin, 'isatty', return_value=True), \
                    patch.object(probe.getpass, 'getpass', return_value='ACCOUNT_SECRET'), \
                    patch.object(probe, 'Reader', return_value=reader), patch('sys.stdout', new_callable=io.StringIO) as output:
                probe.main()
            report = json.loads(report_path.read_text())
            self.assertEqual('resource', report['tokenSelection']['tokenKind'])
            self.assertEqual('dummy_matches', report['samples'][0]['directRead']['result'])
            self.assertEqual('individual_item_api_not_yet_proven', report['samples'][0]['discovery']['status'])
            self.assertEqual(16, len(report['sourceFingerprint']))
            saved = report_path.read_text() + setup_path.read_text() + output.getvalue()
            for secret in ('ACCOUNT_SECRET', 'RESOURCE_SECRET', 'private-name'):
                self.assertNotIn(secret, saved)

    def test_transport_blocks_redirects_and_bounds_responses(self):
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                calls.append((self.command, self.path, self.headers.get('X-Plex-Token')))
                if self.path == '/redirect':
                    self.send_response(302)
                    self.send_header('Location', '/must-not-follow')
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'x' * (probe.MAX_BODY + 1) if self.path == '/oversize' else b'{"ok":true}')
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            root = 'http://127.0.0.1:' + str(server.server_port)
            reader = probe.Reader()
            self.assertEqual(('ok', {'ok': True}), reader.get(root + '/ok', 'hidden-secret'))
            self.assertEqual(('redirect_blocked', None), reader.get(root + '/redirect', 'hidden-secret'))
            self.assertEqual(('oversized_response', None), reader.get(root + '/oversize', 'hidden-secret'))
            self.assertEqual(['/ok', '/redirect', '/oversize'], [c[1] for c in calls])
            self.assertTrue(all(c[0] == 'GET' and c[2] == 'hidden-secret' for c in calls))
            self.assertTrue(all('hidden-secret' not in c[1] for c in calls))
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == '__main__':
    unittest.main()
