#!/usr/bin/env python3
"""Read-only Plex sharing proof. No real MusicGrabber credentials or raw responses are saved."""
import argparse
import datetime as dt
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_BODY = 1024 * 1024
MAX_RECORD = 16 * 1024
PAGE_SIZE = 50
MAX_PAGES = 10


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # Even mistaken command-line token input must not be echoed by argparse.
        super().error('Invalid command or options; use --help. Enter tokens only at the hidden prompt.')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    def invalid_constant(value):
        raise ValueError('Non-finite JSON number')
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)


def same_json(left, right):
    # JSON booleans/numbers must not compare equal through Python's True == 1 rule.
    return json.dumps(left, sort_keys=True, allow_nan=False) == json.dumps(right, sort_keys=True, allow_nan=False)


def base_url(value, allow_local_http=False):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment
            or parsed.path not in ('', '/') or not parsed.hostname or '\\' in value
            or any(ord(c) < 33 for c in value)):
        raise ValueError('Use a Plex server origin without credentials, path, query, or fragment')
    if parsed.scheme != 'https':
        try:
            local = ipaddress.ip_address(parsed.hostname).is_private
        except ValueError:
            local = parsed.hostname == 'localhost'
        if not (allow_local_http and parsed.scheme == 'http' and local):
            raise ValueError('Use HTTPS, or explicitly allow HTTP to a private literal IP or localhost')
    _ = parsed.port  # Validate port before prompting for a token.
    return value.rstrip('/')


def number(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9]+', value):
        raise ValueError('Plex library and item IDs must be numeric')
    return value


def dummy_record(server, library):
    return {
        'type': 'harmonicast.acquisition', 'version': 1,
        'configurationId': str(uuid.uuid4()), 'revision': 1,
        'plexServerId': server, 'musicLibraryId': library,
        'allowAcquisition': False, 'allowRoomAcquisition': False,
        'musicGrabber': {'url': 'https://harmonicast-sharing-proof.invalid',
                        'username': 'DUMMY-NOT-A-REAL-ACCOUNT', 'password': 'DUMMY-NOT-A-REAL-PASSWORD'},
    }


def validate_setup(setup):
    for key in ('musicLibraryId', 'configurationLibraryId', 'itemId'):
        number(setup[key])
    if setup['musicLibraryId'] == setup['configurationLibraryId']:
        raise ValueError('Use a separate private configuration library')
    server = setup['serverId']
    if not isinstance(server, str) or not server.strip() or len(server) > 256:
        raise ValueError('Supply the selected Plex machine identifier')
    expected = setup['dummyRecord']
    canonical = dummy_record(server, setup['musicLibraryId'])
    canonical['configurationId'] = str(uuid.UUID(expected['configurationId']))
    if not same_json(expected, canonical):
        raise ValueError('This probe accepts only its disabled dummy record; never supply real credentials')
    return setup


def write_json(path, value):
    # Do not overwrite earlier evidence or follow a preexisting symlink.
    with open(path, 'x', encoding='utf-8') as handle:
        os.chmod(path, 0o600)
        json.dump(value, handle, indent=2)
        handle.write('\n')


class Reader:
    def __init__(self):
        self.client_id = 'harmonicast-sharing-proof-' + str(uuid.uuid4())
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def get(self, url, token):
        return self.request(url, token)

    def request(self, url, token='', data=None):
        request = urllib.request.Request(url, data=data, method='POST' if data is not None else 'GET', headers={
            'Accept': 'application/json', 'X-Plex-Token': token,
            'X-Plex-Product': 'Harmonicast Sharing Proof',
            'X-Plex-Client-Identifier': self.client_id,
        })
        try:
            with self.opener.open(request, timeout=10) as response:
                raw = response.read(MAX_BODY + 1)
                if len(raw) > MAX_BODY:
                    return 'oversized_response', None
                try:
                    value = strict_json(raw)
                except (ValueError, UnicodeError, RecursionError):
                    return 'invalid_response', None
                return 'ok', value
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                return 'denied_' + str(status), None
            if 300 <= status < 400:
                return 'redirect_blocked', None
            return 'http_' + str(status), None
        except (OSError, ValueError):
            return 'network_error', None


def browser_login(reader, expected_user, emit=print, sleep=time.sleep, clock=time.monotonic):
    """Link through Plex; keep the resulting token only in this process."""
    status, pin = reader.request('https://plex.tv/api/v2/pins', data=b'')
    if status != 'ok' or not isinstance(pin, dict):
        raise ValueError('Could not start Plex sign-in')
    pin_id = str(pin.get('id', ''))
    code = pin.get('code')
    if not pin_id.isdigit() or not isinstance(code, str) or not re.fullmatch(r'[A-Za-z0-9]{4}', code):
        raise ValueError('Invalid Plex linking response')
    emit('In the test account browser, open https://plex.tv/link and enter: ' + code)
    emit('This code links this local Harmonicast Sharing Proof process. Plex may not display the app name.')
    emit('Complete linking in Plex. Waiting up to five minutes; no token will be printed or saved.')
    deadline = clock() + 300
    while clock() < deadline:
        status, result = reader.get('https://plex.tv/api/v2/pins/' + pin_id + '?code=' + code, '')
        if status != 'ok' or not isinstance(result, dict):
            raise ValueError('Plex sign-in failed or expired')
        token = result.get('authToken')
        if token is not None:
            if not isinstance(token, str) or not token or any(ord(c) < 33 for c in token):
                raise ValueError('Invalid sign-in response')
            status, user = reader.get('https://plex.tv/api/v2/user', token)
            if (status != 'ok' or not isinstance(user, dict)
                    or str(user.get('username', '')).casefold() != expected_user.casefold()):
                raise ValueError('Wrong Plex account; no server requests made')
            emit('Expected test account verified. Running read-only checks.')
            return token
        sleep(2)
    raise ValueError('Plex sign-in timed out')


def container(value):
    if not isinstance(value, dict) or not isinstance(value.get('MediaContainer'), dict):
        raise ValueError('Invalid container')
    return value['MediaContainer']


def account_check(reader, token):
    status, user = reader.get('https://plex.tv/api/v2/user', token)
    valid = status == 'ok' and isinstance(user, dict) and bool(user.get('id'))
    # A stable pseudonym lets reviewers distinguish test accounts without recording names/emails.
    identity = hashlib.sha256(str(user['id']).encode()).hexdigest()[:16] if valid else None
    return {'status': status, 'valid': valid, 'accountFingerprint': identity}


def select_token(reader, account_token, setup, mode):
    status, resources = reader.get('https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1', account_token)
    if status != 'ok' or not isinstance(resources, list):
        return account_token, {'resourcesStatus': status, 'resourceMatches': None, 'tokenKind': 'account', 'owned': None}
    matches = [r for r in resources if isinstance(r, dict) and r.get('clientIdentifier') == setup['serverId']
               and 'server' in str(r.get('provides', '')).split(',')]
    evidence = {'resourcesStatus': status, 'resourceMatches': len(matches), 'tokenKind': 'account', 'owned': None}
    if len(matches) == 1:
        resource = matches[0]
        evidence['owned'] = resource.get('owned') in (True, 1, '1')
        if mode == 'resource' and isinstance(resource.get('accessToken'), str) and resource['accessToken']:
            evidence['tokenKind'] = 'resource'
            return resource['accessToken'], evidence
    if mode == 'resource':
        evidence['tokenKind'] = 'account_fallback_no_unique_resource_token'
    return account_token, evidence


def inspect_item(value, setup):
    items = container(value).get('Metadata')
    if not isinstance(items, list) or len(items) != 1 or not isinstance(items[0], dict):
        return {'result': 'ambiguous_or_missing_metadata'}
    item = items[0]
    provenance = (str(item.get('ratingKey')) == setup['itemId'] and item.get('type') in ('album', 9)
                  and str(item.get('librarySectionID')) == setup['configurationLibraryId'])
    summary = item.get('summary', '')
    if not isinstance(summary, str) or len(summary.encode('utf-8')) > MAX_RECORD:
        return {'result': 'invalid_or_oversized_record', 'itemBindingMatches': provenance}
    try:
        matches = same_json(strict_json(summary), setup['dummyRecord'])
    except (ValueError, RecursionError):
        matches = False
    fields = item.get('Field', [])
    locked = any(isinstance(f, dict) and f.get('name') == 'summary' and f.get('locked') in (True, 1, '1')
                 for f in fields) if isinstance(fields, list) else False
    return {'result': 'dummy_matches' if matches and provenance else 'record_or_binding_mismatch',
            'itemBindingMatches': provenance, 'summaryMatches': matches, 'summaryLockReported': locked}


def library_discovery(reader, token, setup):
    """Fallback only: bounded album enumeration in a deliberately shared private library."""
    found = set()
    candidates = set()
    offset = 0
    seen_keys = set()
    for page in range(MAX_PAGES):
        url = (setup['baseUrl'] + '/library/sections/' + setup['configurationLibraryId'] + '/all'
               + '?type=9&X-Plex-Container-Start=' + str(offset) + '&X-Plex-Container-Size=' + str(PAGE_SIZE))
        status, value = reader.get(url, token)
        if status != 'ok':
            return {'status': status, 'complete': False, 'pages': page + 1}
        try:
            body = container(value)
            items = body.get('Metadata', [] if body.get('size') == 0 else None)
            if not isinstance(items, list) or any(not isinstance(i, dict) for i in items) or len(items) > PAGE_SIZE:
                raise ValueError()
            keys = tuple(str(i.get('ratingKey', '')) for i in items)
            if any(not re.fullmatch(r'[0-9]+', key) for key in keys):
                raise ValueError()
            if len(set(keys)) != len(keys) or seen_keys.intersection(keys):
                return {'status': 'repeated_page', 'complete': False, 'pages': page + 1}
            seen_keys.update(keys)
            if ('size' in body and (type(body['size']) is not int or body['size'] != len(items))
                    or 'offset' in body and (type(body['offset']) is not int or body['offset'] != offset)):
                raise ValueError()
            for item in items:
                if str(item.get('ratingKey')) == setup['itemId']:
                    found.add(setup['itemId'])
                summary = item.get('summary', '')
                if isinstance(summary, str) and len(summary.encode()) <= MAX_RECORD:
                    try:
                        record = strict_json(summary)
                        if isinstance(record, dict) and record.get('type') == 'harmonicast.acquisition':
                            candidates.add(str(item.get('ratingKey', '')))
                    except (ValueError, RecursionError):
                        pass
            offset += len(items)
            total = body.get('totalSize')
            if total is not None and (type(total) is not int or total < offset):
                raise ValueError()
            complete = (total is not None and offset >= total) or (total is None and len(items) < PAGE_SIZE)
            if not items and total is not None and offset < total:
                raise ValueError()
            if complete:
                return {'status': 'ok', 'complete': True, 'pages': page + 1,
                        'targetDiscovered': bool(found), 'configurationCandidates': len(candidates)}
        except (ValueError, TypeError):
            return {'status': 'invalid_page', 'complete': False, 'pages': page + 1}
    return {'status': 'page_limit', 'complete': False, 'pages': MAX_PAGES}


def sample(reader, account_token, server_token, setup, mechanism):
    result = {'atUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'account': account_check(reader, account_token)}
    status, root = reader.get(setup['baseUrl'] + '/', server_token)
    result['server'] = {'status': status, 'identityMatches': False}
    if status == 'ok':
        try:
            result['server']['identityMatches'] = container(root).get('machineIdentifier') == setup['serverId']
        except ValueError:
            result['server']['status'] = 'invalid_response'
    # Still test direct metadata after root denial: a known item ID must not bypass permissions.
    status, item = reader.get(setup['baseUrl'] + '/library/metadata/' + setup['itemId'], server_token)
    result['directRead'] = {'status': status}
    if status == 'ok':
        try:
            result['directRead'].update(inspect_item(item, setup))
        except (ValueError, TypeError):
            result['directRead']['result'] = 'invalid_response'
    status, sections = reader.get(setup['baseUrl'] + '/library/sections', server_token)
    result['sections'] = {'status': status}
    if status == 'ok':
        try:
            body = container(sections)
            items = body.get('Directory', [] if body.get('size') == 0 else None)
            if not isinstance(items, list) or any(not isinstance(i, dict) for i in items):
                raise ValueError()
            keys = {str(i.get('key')) for i in items if i.get('type') in ('artist', 8)}
            result['sections'].update({'musicLibraryVisible': setup['musicLibraryId'] in keys,
                                       'configurationLibraryVisible': setup['configurationLibraryId'] in keys})
        except (ValueError, TypeError):
            result['sections']['status'] = 'invalid_response'
    result['discovery'] = (library_discovery(reader, server_token, setup) if mechanism == 'library'
                           else {'status': 'individual_item_api_not_yet_proven', 'complete': False})
    # This is evidence, not an automated security verdict. Denial with an invalid account is inconclusive.
    result['accountControlValid'] = result['account']['valid']
    return result


def sample_progress(entry):
    """Describe only known outcomes; never interpolate response fields."""
    direct = entry.get('directRead', {})
    if not entry.get('accountControlValid'):
        return 'Account control failed; access result is inconclusive.'
    if direct.get('status') in ('denied_401', 'denied_403'):
        return 'Valid account; direct test-album read denied.'
    if direct.get('status') == 'ok' and direct.get('result') == 'dummy_matches':
        return 'Valid account; direct test-album read matches the dummy record.'
    return 'Valid account; direct test-album read requires review.'


def main():
    parser = SafeParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    configure = sub.add_parser('configure', help='Create a local disabled dummy record and non-secret setup file')
    for name in ('base-url', 'server-id', 'music-library-id', 'configuration-library-id', 'item-id', 'output-dir'):
        configure.add_argument('--' + name, required=True)
    configure.add_argument('--allow-local-http', action='store_true')
    run = sub.add_parser('run', help='Prompt privately for one account token; write sanitized read-only evidence')
    run.add_argument('--setup', required=True)
    run.add_argument('--role', required=True, choices=('owner', 'approved', 'unapproved'))
    run.add_argument('--phase', required=True, choices=('before-share', 'shared', 'removed', 'restart', 'metadata-refresh'))
    run.add_argument('--mechanism', choices=('item', 'library'), default='item')
    run.add_argument('--browser-login', action='store_true', help='Link through plex.tv instead of copying a token')
    run.add_argument('--expected-user', help='Required for browser login; checked before server access, never included in evidence')
    run.add_argument('--token-mode', choices=('resource', 'account'), default='resource')
    run.add_argument('--watch-seconds', type=int, default=0)
    run.add_argument('--interval-seconds', type=int, default=15)
    run.add_argument('--output', required=True)
    run.add_argument('--allow-local-http', action='store_true')
    args = parser.parse_args()
    if args.command == 'configure':
        setup = {'baseUrl': base_url(args.base_url, args.allow_local_http), 'serverId': args.server_id,
                 'musicLibraryId': args.music_library_id, 'configurationLibraryId': args.configuration_library_id,
                 'itemId': args.item_id, 'dummyRecord': dummy_record(args.server_id, args.music_library_id)}
        validate_setup(setup)
        target = Path(args.output_dir)
        target.mkdir(parents=True, exist_ok=False, mode=0o700)
        write_json(target / 'setup.json', setup)
        write_json(target / 'dummy-record.json', setup['dummyRecord'])
        print('Created setup.json and dummy-record.json. Publish only the dummy record into the test album summary.')
        return
    if not 0 <= args.watch_seconds <= 600 or not 10 <= args.interval_seconds <= 120:
        raise ValueError('Watch must be 0–600 seconds and interval 10–120 seconds')
    path = Path(args.setup)
    if path.stat().st_size > MAX_RECORD:
        raise ValueError('Setup file is too large')
    setup = validate_setup(strict_json(path.read_text(encoding='utf-8')))
    setup['baseUrl'] = base_url(setup['baseUrl'], args.allow_local_http)
    if Path(args.output).exists():
        raise ValueError('Choose a new report path to preserve earlier evidence')
    reader = Reader()
    if args.browser_login:
        if not args.expected_user or not args.expected_user.strip():
            raise ValueError('Browser login requires the expected test username')
        token = browser_login(reader, args.expected_user)
    else:
        if not sys.stdin.isatty():
            raise ValueError('Run interactively so the token prompt cannot echo')
        token = getpass.getpass('Plex ACCOUNT token (hidden; never saved): ').strip()
        if not token or any(ord(c) < 33 for c in token):
            raise ValueError('Token must be nonblank and contain no whitespace')
    control = account_check(reader, token)
    if not control['valid']:
        raise ValueError('Account token could not be validated at plex.tv; no access-denial conclusion can be drawn')
    server_token, selection = select_token(reader, token, setup, args.token_mode)
    binding = '|'.join(setup[k] for k in ('serverId', 'musicLibraryId', 'configurationLibraryId', 'itemId')) + '|' + setup['dummyRecord']['configurationId']
    report = {'schemaVersion': 1, 'sourceFingerprint': hashlib.sha256(binding.encode()).hexdigest()[:16], 'role': args.role, 'phase': args.phase, 'mechanism': args.mechanism,
              'tokenSelection': selection, 'samples': [], 'liveAcceptance': 'requires_cross_account_and_manual_review'}
    start = time.monotonic()
    while True:
        entry = sample(reader, token, server_token, setup, args.mechanism)
        entry['elapsedSeconds'] = round(time.monotonic() - start, 2)
        report['samples'].append(entry)
        print(sample_progress(entry), flush=True)
        if time.monotonic() - start >= args.watch_seconds:
            break
        time.sleep(min(args.interval_seconds, max(0, args.watch_seconds - (time.monotonic() - start))))
    write_json(args.output, report)
    print('Saved sanitized evidence. No automatic pass/fail claim; compare accounts and complete the manual checklist.')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit('Stopped; no credentials saved.')
    except (ValueError, KeyError, TypeError, OSError, RecursionError):
        # Input files and network errors can contain tokens/URLs. Never echo them or a traceback.
        raise SystemExit('Probe stopped: check inputs, HTTPS/local-HTTP choice, account token, and unused output paths. No credentials saved.')
