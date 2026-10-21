#!/usr/bin/env python3
"""Drive the REPL's line editor through a real pty (needs pexpect).

Each case types keys into a fresh `clove` REPL and expects a result that
only comes out if the editor assembled the right form. Skips (exit 0) when
pexpect is missing. Usage: tests/pty_repl.py [path/to/clove]"""
import os, sys, tempfile, time

try:
    import pexpect
except ImportError:
    print("pty tests skipped: no pexpect")
    sys.exit(0)

CLOVE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "./clove")
ESC, ENTER, ALT_ENTER, UP = "\x1b", "\r", "\x1b\r", "\x1b[A"

def repl(mode, home):
    env = dict(os.environ, HOME=home, CLOVE_EDIT_MODE=mode, TERM="xterm")
    env.pop("ANTHROPIC_API_KEY", None)
    p = pexpect.spawn(CLOVE, ["repl"], env=env, encoding="utf-8", timeout=5,
                      dimensions=(40, 120))
    p.expect("clove\\[1\\]> ")
    time.sleep(0.2)   # raw mode is set with TCSAFLUSH: let it settle
    return p

def keys(p, *ks):
    for k in ks:
        p.send(k)
        time.sleep(0.12 if k.startswith(ESC) else 0.03)

CASES = [
    # (name, mode, keys..., expected output)
    ("type over auto-closed paren", "emacs", ["(* 6 7)", ENTER], "42"),
    ("alt-enter continues, indent", "emacs",
     ["(let [x 40]", ALT_ENTER, "(+ x 2", ENTER], "42"),
    ("char literal paren", "emacs", ['(str \\( "x")', ENTER], '"(x"'),
    ("bracketed paste", "emacs",
     ["\x1b[200~(+ 40\n   2)\x1b[201~", ENTER], "42"),
    ("vi ciw", "vi", ["(inc 99)", ESC, "b", "ciw", "41", ESC, ENTER], "42"),
    ("vi dd u", "vi", ["(* 21 2)", ESC, "dd", "u", ENTER], "42"),
    ("vi f/x/A", "vi", ["(+ 400 2)", ESC, "0", "f0", "x", "A", ENTER], "42"),
    ("propose line is editable", "emacs",
     ['(clove/repl-edit* "p> " "(+ 40 2)")', ENTER, ENTER], '"(+ 40 2)"'),
]

def run_case(name, mode, ks, want, home):
    p = repl(mode, home)
    keys(p, *ks)
    try:
        p.expect_exact(want)
        ok = True
    except (pexpect.TIMEOUT, pexpect.EOF):
        ok = False
        print(f"FAIL {name}: wanted {want!r}; saw {p.before[-300:]!r}")
    p.sendcontrol("d")
    p.close()
    return ok

def main():
    home = tempfile.mkdtemp()
    cases = list(CASES)
    # an unfinished form: Enter opens a line instead of submitting
    cases.append(("unbalanced enter newline", "emacs",
                  ["\x1b[200~(+ 40\x1b[201~", ENTER, "2)", ENTER], "42"))
    # vi `.` repeats the last change (x)
    cases.append(("vi dot", "vi", ["(+ 4422)", ESC, "0", "f2", "x", ".", ENTER], "m 44\r"))
    fails = sum(not run_case(*c, home) for c in cases)
    # history: the last multi-line form comes back whole with ↑
    p = repl("emacs", home)
    keys(p, "(let [a 2]", ALT_ENTER, "(* a 21", ENTER)
    p.expect_exact("42")
    keys(p, "(+ 1 1)", ENTER)
    p.expect_exact("2")
    keys(p, UP, UP, ENTER)
    try:
        p.expect("42")
    except (pexpect.TIMEOUT, pexpect.EOF):
        fails += 1
        print("FAIL history recall")
    p.sendcontrol("d"); p.close()
    hist = open(os.path.join(home, ".clove_history")).read()
    if not hist.startswith("#clove-history-v2\n") or "(let [a 2]\\n  (* a 21))" not in hist:
        fails += 1
        print("FAIL history file:", repr(hist))
    total = len(cases) + 2
    print(f"pty tests: {total - fails} passed, {fails} failed")
    sys.exit(1 if fails else 0)

main()
