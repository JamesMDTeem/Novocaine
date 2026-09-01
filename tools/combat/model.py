"""The combat model in Python - the follower half of ADR-0002.

`src/haven/combat/Formulas.java` is authoritative: it is the side that has to match
the live game and the side the bot runs. This is a hand-written mirror of it, not a
translation of it, and `model_check.py` holds it to golden vectors generated from the
Java. Neither file is generated from the other, and when they disagree the Java is
right by construction.

Why two implementations at all: fitting an animal's hidden stats means evaluating the
forward model thousands of times while varying parameters, which Python does well and
Java does awkwardly; the bot must evaluate the same model inside a frame budget, which
Java does and Python cannot. See the ADR for the argument.

Stdlib only. Every function here is pure.
"""

import math

GREEN, BLUE, YELLOW, RED = 0, 1, 2, 3


def raw_damage(basedmg, share, ql, str_, opening):
    """Damage before armour: basedmg * share * sqrt(sqrt(ql * str) / 10) * opening^2.

    `opening` is the opening in the attack's own school, 0..1 - not the combined
    opening over all four colours. Using the combined value inflates the opening
    whenever another colour is up and understates the coefficient."""
    return basedmg * share * math.sqrt(math.sqrt(ql * str_) / 10.0) * opening * opening


def dealt_damage(raw, hard, soft, armpen):
    """Damage after penetration and soak.

    Penetration applies before any armour calculation; hard soak comes off the top;
    soft soak ramps in as 1 - (1 - x)^2 over an interval of twice its value."""
    pen = raw * armpen
    r = max(0.0, (raw - pen) - hard)
    if soft <= 0:
        return pen + r
    x = min(1.0, r / (2.0 * soft))
    return pen + r - (soft * (1.0 - ((1.0 - x) * (1.0 - x))))


def opening_gain(wa, wd, ob, oc):
    """How much a move raises one of the defender's openings: cbrt(Wa/Wd) * Ob * (1-Oc).

    `ob` is in percentage units (20 for +20%) and the result is in the same units."""
    if wd <= 0:
        return 0.0
    return _cbrt(wa / wd) * ob * (1.0 - oc)


def defence_weight(wa, gain, ob, oc):
    """The defender's defence weight, recovered from an observed gain. Returns 0 when
    the observation cannot constrain it."""
    denom = ob * (1.0 - oc)
    if denom <= 0 or gain <= 0:
        return 0.0
    k = gain / denom
    return wa / (k * k * k)


def combined(openings):
    """Openings combine as 1 - product(1 - o_i). Fractions, 0..1."""
    p = 1.0
    for o in openings:
        p *= (1.0 - o)
    return 1.0 - p


def agility_cooldown_factor(agi_me, agi_foe):
    """1 - 0.1 * clamp(log2(agi_me / agi_foe), -1, +1). Attacks only; maneuvers are
    not modified by relative agility."""
    if agi_me <= 0 or agi_foe <= 0:
        return 1.0
    l = math.log(agi_me / agi_foe) / math.log(2.0)
    l = max(-1.0, min(1.0, l))
    return 1.0 - (0.1 * l)


def cooldown_ticks(base, mu_divides, mu, ip_scale, ip, is_attack, agi_me, agi_foe):
    """A move's cooldown in whole server ticks."""
    cd = base
    if mu_divides and mu > 0:
        cd /= mu
    cd *= (1.0 + (ip_scale * ip))
    if is_attack:
        cd *= agility_cooldown_factor(agi_me, agi_foe)
    return _round_half_up(cd)


def mu_from_cooldown(base, observed_ticks, ip_scale, ip):
    """mu read back from a reported cooldown, for moves whose text divides by it.

    observed = (base / mu) * (1 + ip_scale * ip), so the initiative term comes off the
    observation before the ratio is taken."""
    ipf = 1.0 + (ip_scale * ip)
    if observed_ticks <= 0 or ipf <= 0:
        return 0.0
    return base / (observed_ticks / ipf)


def ticks_to_seconds(ticks):
    """Server ticks are 0.06 seconds."""
    return ticks * 0.06


def _cbrt(x):
    """Java's Math.cbrt is defined for negatives; Python's ** is not."""
    if x < 0:
        return -((-x) ** (1.0 / 3.0))
    return x ** (1.0 / 3.0)


def _round_half_up(x):
    """Java's Math.round is floor(x + 0.5), which differs from Python's round() at
    every .5 - Python rounds half to even, so round(0.5) is 0 and round(2.5) is 2.
    Matching the authoritative side matters here: cooldowns land on halves often
    enough that banker's rounding would disagree with the game by a tick."""
    return int(math.floor(x + 0.5))
