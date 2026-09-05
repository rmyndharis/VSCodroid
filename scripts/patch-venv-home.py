#!/usr/bin/env python3
"""Makes `python -m venv` name a home its new interpreter can start from.

    patch-venv-home.py <venv/__init__.py>
    patch-venv-home.py --check <file-or-directory>
    patch-venv-home.py --self-test

Creating an environment from inside one that is already active fails on device.
What the user sees is the tail of a subprocess failure and nothing about the
cause:

    Error: Command '['<new>/bin/python', '-m', 'ensurepip', '--upgrade',
    '--default-pip']' returned non-zero exit status 1.

Run that child by hand and the real message appears:

    Could not find platform independent libraries <prefix>
    ModuleNotFoundError: No module named 'encodings'

The environment is still created, and it has no pip in it.

WHAT IS AND IS NOT BROKEN. A plain `python3 -m venv` at the top level works
here untouched, and that is worth stating because it is easy to measure the
opposite by accident. `sys.executable` and `sys._base_executable` keep the
UNRESOLVED `usr/bin/python3`, so the directory venv derives already has
`lib/python3.X/` beside it. Start the same interpreter by the resolved path it
is a symlink to, `<nativeLibraryDir>/libpython.so`, and the derived directory is
the native library directory instead, which holds no standard library. That
second shape is what a `.so`-path invocation and `/proc/self/exe` fallbacks
produce, and it is not the one a person hits.

The one a person hits is a venv created from inside an active venv.
`sys._base_executable` is then the outer environment's own `bin`, and the
parent of that does hold a `lib/python3.X/`, containing nothing but
`site-packages`. So `home` names a directory that looks right and is not.

WHY THE NOTE IS THE ONLY CHANNEL. PYTHONHOME, which `Environment.kt` exports,
covers for all of this everywhere else. It cannot cover for it here:
`_call_new_python` copies the environment and pops PYTHONHOME and PYTHONPATH
before spawning the child that installs pip. The comment above that code says
why (gh-98251: not `-I`, because that would mask legitimate user preferences,
but the path variables must not overrule normal venv handling). What is left
for the child to go on is the `home` line in `pyvenv.cfg`, and that is what this
rewrite corrects.

THE LANDMARK IS A FILE, NOT A DIRECTORY. `os.py` is what CPython itself looks
for, and testing the directory instead would read an active venv's
`lib/python3.X/` as a standard library, leaving the one case that actually
reaches users unfixed while appearing to handle it.

Measured on an API 37 emulator, unpatched, with an app-shaped environment:

  * `usr/bin/python3 -m venv v` exits 0 and its `bin/` holds pip, pip3 and
    pip3.14, so the top level was never the problem;
  * `v/bin/python -m venv w` from inside it fails as above, `w/pyvenv.cfg` says
    `home = <v>/bin`, and `w/bin/` holds no pip at all;
  * `<v>/lib/python3.14/` contains `site-packages` and no `os.py`.

With the rewrite applied, editing only that one value in an existing
`pyvenv.cfg` was enough for the child to run: it printed "Successfully installed
pip-26.1.2", and pip then reported itself from the environment's own
site-packages.

SCOPE. On POSIX the rewritten value reaches nothing but the `home` line.
`setup_python` assigns `context.python_dir` to a local and never reads it again,
and the one place that walks it, copying `init.tcl`, is inside the
`os.name == 'nt'` branch. Verified against the shipped tree before this was
written; if a future CPython grows a POSIX use of it, --check still passes while
the assumption behind it has moved, so re-read those sites when the bundled
Python is bumped.
"""

import os
import sys
import tempfile
import textwrap
import types

ANCHOR = """        context.executable = executable
        context.python_dir = dirname
"""

REPLACEMENT = """        context.executable = executable
        # The interpreter this environment will run cannot always find the
        # standard library from the directory derived above, and `home` is the
        # only channel that reaches it: _call_new_python pops PYTHONHOME and
        # PYTHONPATH out of the child that installs pip, and PYTHONHOME is what
        # covers for this everywhere else. See scripts/patch-venv-home.py.
        #
        # os.py, the landmark CPython itself looks for, rather than the
        # directory holding it. An active venv's own lib/python3.X exists and
        # holds nothing but site-packages, so a directory test reads one as a
        # standard library and leaves the case that reaches users unfixed.
        _vscodroid_landmark = os.path.join(
            'lib', 'python%d.%d' % sys.version_info[:2], 'os.py')
        if (not os.path.isfile(os.path.join(os.path.dirname(dirname), _vscodroid_landmark))
                and os.path.isfile(os.path.join(sys.base_prefix, _vscodroid_landmark))
                and os.path.isdir(os.path.join(sys.base_prefix, 'bin'))):
            dirname = os.path.join(sys.base_prefix, 'bin')
        context.python_dir = dirname
"""

MARKER = '_vscodroid_landmark'


def patch(path):
    """Rewrites one venv/__init__.py. Returns True if it changed the file."""
    with open(path, encoding='utf-8') as handle:
        source = handle.read()

    if MARKER in source:
        return False

    if ANCHOR not in source:
        raise SystemExit(
            f'{path}: the two lines this rewrite anchors on are gone.\n'
            'CPython moved `context.python_dir = dirname` in ensure_directories.\n'
            'Re-read venv/__init__.py and re-anchor rather than loosening the match:\n'
            'a silent miss here ships a Python whose venv cannot install pip.'
        )

    with open(path, 'w', encoding='utf-8') as handle:
        handle.write(source.replace(ANCHOR, REPLACEMENT, 1))
    return True


def files_under(target):
    """The venv modules to consider, whether given a file or a tree."""
    if os.path.isfile(target):
        return [target]
    found = []
    for root, _dirs, names in os.walk(target):
        if os.path.basename(root) == 'venv' and '__init__.py' in names:
            found.append(os.path.join(root, '__init__.py'))
    return found


def _decide(dirname, base_prefix):
    """The home the shipped rewrite picks, run over a fake tree.

    Executes REPLACEMENT itself rather than a restatement of it, so this cannot
    go green for a rewrite that is no longer the one being written.
    """
    namespace = {
        'os': os,
        'sys': types.SimpleNamespace(
            base_prefix=base_prefix, version_info=sys.version_info,
        ),
        'context': types.SimpleNamespace(),
        'executable': os.path.join(dirname, 'python3'),
        'dirname': dirname,
    }
    exec(textwrap.dedent(REPLACEMENT), namespace)  # noqa: S102
    return namespace['context'].python_dir


def self_test():
    """The three layouts the guard has to tell apart."""
    stdlib = 'python%d.%d' % sys.version_info[:2]
    with tempfile.TemporaryDirectory() as root:
        base = os.path.join(root, 'usr')
        os.makedirs(os.path.join(base, 'bin'))
        os.makedirs(os.path.join(base, 'lib', stdlib))
        open(os.path.join(base, 'lib', stdlib, 'os.py'), 'w').close()

        # A real installation: the landmark is already beside it, so nothing moves.
        ordinary = os.path.join(base, 'bin')
        assert _decide(ordinary, base) == ordinary, 'the guard moved a correct home'

        # nativeLibraryDir: no lib/ at all beside it.
        native = os.path.join(root, 'lib', 'arm64')
        os.makedirs(native)
        assert _decide(native, base) == ordinary, 'a home with no stdlib was left alone'

        # An active venv: lib/python3.X exists and holds only site-packages. This
        # is the case a directory test gets wrong, and the one users reach.
        venv = os.path.join(root, 'env')
        os.makedirs(os.path.join(venv, 'bin'))
        os.makedirs(os.path.join(venv, 'lib', stdlib, 'site-packages'))
        assert _decide(os.path.join(venv, 'bin'), base) == ordinary, \
            'a venv lib directory was mistaken for a standard library'

        # No base prefix to fall back to: better to leave it than to invent one.
        empty = os.path.join(root, 'nowhere')
        assert _decide(native, empty) == native, 'the guard invented a home'
    print('ok -- venv home decided correctly for an installation, a bare '
          'directory, an active environment and a missing base prefix')


def main(argv):
    if argv[:1] == ['--self-test']:
        self_test()
        return

    check = argv[:1] == ['--check']
    targets = argv[1:] if check else argv
    if not targets:
        raise SystemExit(__doc__)

    for target in targets:
        modules = files_under(target)
        if check:
            # An absent tree is not a pass. The lint and unit-test jobs stub an
            # empty assets directory, so the caller arms this by the directory
            # existing; reaching here with nothing found means the tree was
            # assembled without the standard library.
            if not modules:
                raise SystemExit(f'{target}: no venv/__init__.py found')
            for module in modules:
                with open(module, encoding='utf-8') as handle:
                    if MARKER not in handle.read():
                        raise SystemExit(
                            f'{module}: venv still writes a home that can name a\n'
                            'directory with no standard library under it, so creating an\n'
                            'environment from inside an active one fails while it\n'
                            'bootstraps pip. Re-run scripts/download-python.sh.'
                        )
        else:
            for module in modules:
                if patch(module):
                    print(f'patched {module}')


if __name__ == '__main__':
    main(sys.argv[1:])
