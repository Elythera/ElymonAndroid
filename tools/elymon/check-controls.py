#!/usr/bin/env python3
"""Host checks of Elymon's default touch layout and of its Android key bindings.

What it checks, reading the Java sources of this repository rather than copying them:
  1. assets/default.json is valid JSON, schema version 8, and every object carries exactly
     the fields Gson maps (customcontrols/ControlData, ControlDrawerData,
     ControlJoystickData, CustomControls), with the right JSON types;
  2. every key code exists (LwjglGlfwKeycode, ControlData.SPECIALBTN_*);
  3. positions: each exp4j expression is evaluated the way ControlData.insertDynamicPos
     does (same variables, px()/dp(), button-size scaling of ControlInterface,
     drawer alignment of ControlDrawer) on several screens; no two controls shown
     together overlap, none leaves the screen, and none covers what the game draws there
     (Elymon HUD, Jade, Xaero minimap, hotbar; Cobblemon battle widgets in screens);
  4. keys that type a character are never shown in screens (search boxes);
  5. key bindings: every key the layout, the joystick and the hotbar send is bound in game
     to exactly the expected action once the Android overlay of
     config/defaultoptions/keybindings.txt (assets/elymon/android-policy.json) is applied
     to the pack's file (tools/elymon/controls/pack-keybindings.txt), and the history of
     assets/elymon/controls-keybindings.json covers every overlaid key;
  6. docs/elymon/controls/elymon-default.json is the same file, and its SHA-256 is the
     current entry of ElymonControls.LAYOUTS.

Usage: tools/elymon/check-controls.py [--preview DIR] [--dump FILE] [--verbose]
  --preview DIR  also draws every checked screen as PNG (needs Pillow)
  --dump FILE    also writes every evaluated expression, for tools/elymon/test-controls.sh
                 to recompute with the real exp4j
Exit status 1 when a check fails.
"""
import hashlib
import json
import math
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
APP = os.path.join(REPO, "app_pojavlauncher", "src", "main")
JAVA = os.path.join(APP, "java")
PKG = os.path.join(JAVA, "net", "kdt", "pojavlaunch")
LAYOUT = os.path.join(APP, "assets", "default.json")
DOC_COPY = os.path.join(REPO, "docs", "elymon", "controls", "elymon-default.json")
POLICY = os.path.join(APP, "assets", "elymon", "android-policy.json")
KEY_HISTORY = os.path.join(APP, "assets", "elymon", "controls-keybindings.json")
PACK_KEYBINDINGS = os.path.join(REPO, "tools", "elymon", "controls", "pack-keybindings.txt")
PACK_KEYBINDINGS_MD5 = "6784f31c6ce3c49370d35509b9680262"  # distribution 2026-09-26, Elymon 2.4.6
ELYMON_CONTROLS = os.path.join(JAVA, "com", "elythera", "elymon", "controls", "ElymonControls.java")
KEYBINDINGS_PATH = "config/defaultoptions/keybindings.txt"

errors = []
warnings = []
notes = []


def error(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def read(path, mode="r"):
    with open(path, mode, encoding=None if "b" in mode else "utf-8") as f:
        return f.read()


# =================================================================== Java sources

def java_fields(path):
    """Instance fields of the top-level class, as {name: java type}: what Gson maps."""
    text = read(path)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    fields = {}
    depth = 0
    statement = ""
    for ch in text:
        if ch == "{":
            depth += 1
            statement = ""
            continue
        if ch == "}":
            depth -= 1
            statement = ""
            continue
        if depth != 1:
            continue
        statement += ch
        if ch == ";":
            s = " ".join(statement.split())
            statement = ""
            if "(" in s.split("=")[0]:
                continue
            m = re.match(r"^(?:@\w+\s+)*((?:public|private|protected|static|final|transient|volatile)\s+)*"
                         r"([\w.<>\[\], ]+?)\s+(\w+(?:\s*,\s*\w+)*)\s*(?:=.*)?;$", s)
            if not m:
                continue
            modifiers = re.findall(r"\b(static|transient)\b", s.split("=")[0])
            if modifiers:
                continue
            java_type = m.group(2).strip()
            for name in m.group(3).split(","):
                fields[name.strip()] = java_type
    return fields


def java_enum(path, enum_name):
    text = read(path)
    m = re.search(r"enum\s+%s\s*\{([^}]*)\}" % enum_name, text)
    return [v.strip() for v in m.group(1).split(",") if v.strip()]


def glfw_keycodes():
    """{code: name} for GLFW_KEY_* and the set of codes that type a character."""
    text = read(os.path.join(PKG, "LwjglGlfwKeycode.java"))
    codes = {}
    for name, value in re.findall(r"\b(GLFW_KEY_\w+)\s*=\s*(-?\d+)", text):
        codes[int(value)] = name
    printable_block = text[text.index("Printable keys"):text.index("Function keys")]
    printable = {int(v) for v in re.findall(r"GLFW_KEY_\w+\s*=\s*(\d+)", printable_block)}
    # keypad digits and operators also type a character (KeyCharacterMap display label)
    printable |= {c for c, n in codes.items() if n.startswith("GLFW_KEY_KP_")}
    return codes, printable


def special_keycodes():
    text = read(os.path.join(PKG, "customcontrols", "ControlData.java"))
    return {int(v): n for n, v in re.findall(r"\b(SPECIALBTN_\w+)\s*=\s*(-\d+)", text)}


# =================================================================== exp4j subset

FUNCTIONS = {"abs": abs, "floor": math.floor, "ceil": math.ceil}


def tokenize(expr):
    tokens = []
    i = 0
    while i < len(expr):
        c = expr[i]
        if c.isspace():
            i += 1
        elif c.isdigit() or c == ".":
            m = re.match(r"\d*\.?\d+(?:[eE][-+]?\d+)?", expr[i:])
            tokens.append(("num", float(m.group(0))))
            i += len(m.group(0))
        elif c.isalpha() or c == "_":
            m = re.match(r"[A-Za-z_]\w*", expr[i:])
            tokens.append(("name", m.group(0)))
            i += len(m.group(0))
        elif c in "+-*/^%(),":
            tokens.append(("op", c))
            i += 1
        else:
            raise ValueError("unexpected character %r" % c)
    return tokens


class Parser:
    """exp4j precedence: + - 500, * / % 1000, unary +/- 5000, ^ 10000 right-associative."""

    def __init__(self, tokens, functions):
        self.t = tokens
        self.i = 0
        self.functions = functions

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else (None, None)

    def take(self):
        tok = self.peek()
        self.i += 1
        return tok

    def parse(self):
        value = self.additive()
        if self.i != len(self.t):
            raise ValueError("trailing tokens")
        return value

    def additive(self):
        v = self.multiplicative()
        while self.peek() in (("op", "+"), ("op", "-")):
            op = self.take()[1]
            r = self.multiplicative()
            v = v + r if op == "+" else v - r
        return v

    def multiplicative(self):
        v = self.unary()
        while self.peek() in (("op", "*"), ("op", "/"), ("op", "%")):
            op = self.take()[1]
            r = self.unary()
            if op == "*":
                v = v * r
            elif r == 0:
                raise ZeroDivisionError("exp4j throws ArithmeticException: Division by zero!")
            elif op == "/":
                v = v / r
            else:
                v = math.fmod(v, r)
        return v

    def unary(self):
        if self.peek() == ("op", "-"):
            self.take()
            return -self.unary()
        if self.peek() == ("op", "+"):
            self.take()
            return self.unary()
        return self.power()

    def power(self):
        base = self.atom()
        if self.peek() == ("op", "^"):
            self.take()
            return base ** self.unary()
        return base

    def atom(self):
        kind, value = self.take()
        if kind == "num":
            return value
        if kind == "op" and value == "(":
            v = self.additive()
            if self.take() != ("op", ")"):
                raise ValueError("missing )")
            return v
        if kind == "name":
            if value not in self.functions:
                raise ValueError("unknown function or variable %s" % value)
            if self.take() != ("op", "("):
                raise ValueError("%s needs (" % value)
            arg = self.additive()
            if self.take() != ("op", ")"):
                raise ValueError("%s: one argument only" % value)
            return self.functions[value](arg)
        raise ValueError("unexpected token %r" % (value,))


def evaluate(expr, variables, density):
    """ControlData.insertDynamicPos: plain ${name} substitution, then exp4j."""
    text = expr
    for name, value in variables.items():
        text = text.replace("${" + name + "}", value)
    if "${" in text:
        raise ValueError("unknown variable in %s" % expr)
    functions = dict(FUNCTIONS)
    functions["px"] = lambda dp: dp * density
    functions["dp"] = lambda px: px / density
    return Parser(tokenize(text), functions).parse()


def jfloat(v):
    """Float.toString, close enough for exp4j (it only needs a number)."""
    return repr(float(v))


# =================================================================== screens and zones

class Screen:
    def __init__(self, label, width, height, density):
        self.label = label
        self.W = width
        self.H = height
        self.d = density
        # upper bound of Minecraft's automatic GUI scale, in physical pixels per GUI pixel
        self.g = min(width / 320.0, height / 240.0)

    def __str__(self):
        return "%s %dx%d@%g (%dx%d dp)" % (self.label, self.W, self.H, self.d,
                                           round(self.W / self.d), round(self.H / self.d))


SCREENS = [
    Screen("20:9", 2340, 1080, 2.625),
    Screen("20:9", 2340, 1080, 2.8125),
    Screen("20:9", 2340, 1080, 3.0),
    Screen("S24U-cutout", 2244, 1080, 2.8125),
    Screen("20:9", 1600, 720, 1.75),
    Screen("20:9", 1600, 720, 2.0),
    Screen("20:9", 2400, 1080, 2.625),
    Screen("20:9", 2400, 1080, 3.0),
    Screen("4:3", 2048, 1536, 2.0),
    Screen("4:3", 2048, 1536, 2.25),
    Screen("16:9", 1920, 1080, 2.625),
    Screen("16:9", 1920, 1080, 3.0),
    Screen("16:10", 2560, 1600, 2.5),
]
REQUIRED_RESOLUTIONS = {(2340, 1080), (1600, 720), (2400, 1080), (2048, 1536)}
BUTTON_SCALES = [100, 80, 120]  # 100 must pass; the others are reported


def zones(s):
    """Rectangles (left, top, right, bottom) in px that controls must leave free."""
    W, H, g = s.W, s.H, s.g
    game = {
        # elymon-mod HudPillsLayer: TOP_MARGIN 3 + PILL_HEIGHT 13, SIDE_MARGIN 16
        "Elymon HUD pills": (16 * g, 0, W - 16 * g, 17 * g),
        # Jade's tooltip (182 px band) and the territory title under the pills
        "Jade and territory title": (W / 2 - 100 * g, 0, W / 2 + 100 * g, 56 * g),
        # Xaero minimap in the top-right corner, with coordinates and biome under it
        "Xaero minimap": (W - 76 * g, 0, W, 86 * g),
        # HotbarView: 180 x 20 GUI pixels, bottom centre, takes the touches (22 with its frame)
        "hotbar": (W / 2 - 92 * g, H - 23 * g, W / 2 + 92 * g, H),
    }
    game_soft = {
        # XP bar, hearts, armour and food over the hotbar: covered only by a few pixels at most
        "status bars": (W / 2 - 92 * g, H - 50 * g, W / 2 + 92 * g, H - 23 * g),
        # Cobblemon PartyOverlay: slots 62 x 30, spacing 4, six slots centred vertically
        "Cobblemon party": (0, H / 2 - 100 * g, 62 * g, H / 2 + 100 * g),
    }
    screen = {
        # BattleOverlay: HORIZONTAL_INSET 12, TILE_WIDTH 140, VERTICAL_INSET 10, TILE_HEIGHT 40
        "battle tiles (left)": (0, 0, 154 * g, 52 * g),
        "battle tiles (right)": (W - 154 * g, 0, W, 52 * g),
        # BattleGeneralActionSelection / BattleMoveSelection at (12..217, guiH-85..), back button (9, guiH-22)
        "battle actions": (0, H - 88 * g, 220 * g, H),
        # BattleMessagePane: 169 x 55 (101 expanded) at (guiW-181, guiH-30-height)
        "battle log": (W - 184 * g, H - 134 * g, W, H - 28 * g),
        # BattleSwitchPokemonSelection: tiles from (guiW/2 - 95, guiH - 192), 2 x 3 slots of 98 x 31
        "battle switch panel": (W / 2 - 96 * g, H - 194 * g, W / 2 + 100 * g, H - 98 * g),
        # a 176-wide container (inventory, chests) in the middle
        "container screens": (W / 2 - 90 * g, 0, W / 2 + 90 * g, H),
    }
    screen_soft = {
        # ChatScreen: history 320 x 180 GUI px from (2, guiH - 40) upwards, newest lines at the bottom
        "chat history": (0, H - 220 * g, 330 * g, H - 40 * g),
    }
    return game, game_soft, screen, screen_soft


# =================================================================== layout model

class Control:
    def __init__(self, label, kind, data, rect, game, menu, parent=None):
        self.label = label
        self.kind = kind          # button, drawer, sub, joystick
        self.data = data
        self.rect = rect          # (l, t, r, b) px
        self.game = game          # shown while the game has the cursor
        self.menu = menu          # shown while a screen is open
        self.parent = parent      # drawer of a sub-button


def overlap(a, b, tolerance=0.5):
    return (min(a[2], b[2]) - max(a[0], b[0]) > tolerance) and (min(a[3], b[3]) - max(a[1], b[1]) > tolerance)


def build(layout, s, scale):
    """Evaluates every control the way ControlLayout/ControlInterface/ControlDrawer do."""
    scaled_at = layout["scaledAt"]
    margin = int(2 * s.d)  # ControlInterface.getMarginDistance, (int) in buildConversionMap

    def size(data):
        # preProcessProperties: width * PREF_BUTTONSIZE / scaledAt, then (int) in LayoutParams
        return data["width"] * s.d / scaled_at * scale, data["height"] * s.d / scaled_at * scale

    def place(data, w, h):
        try:
            return evaluate_position(data, w, h)
        except (ValueError, ZeroDivisionError) as e:
            raise ValueError("«%s»: %s" % (str(data.get("name")).replace("\n", " "), e))

    def evaluate_position(data, w, h):
        variables = {
            "top": "0", "left": "0",
            "right": jfloat(s.W - w), "bottom": jfloat(s.H - h),
            "width": jfloat(w), "height": jfloat(h),
            "screen_width": str(s.W), "screen_height": str(s.H),
            "margin": str(margin), "preferred_scale": jfloat(scale),
        }
        return evaluate(data["dynamicX"], variables, s.d), evaluate(data["dynamicY"], variables, s.d)

    def hideable(data):
        return -2 not in data["keycodes"] and -5 not in data["keycodes"]

    controls = []
    for data in layout["mJoystickDataList"]:
        w, h = size(data)
        x, y = place(data, w, h)
        controls.append(Control(data["name"], "joystick", data, (x, y, x + int(w), y + int(h)),
                                data["displayInGame"], data["displayInMenu"]))
    for data in layout["mControlDataList"]:
        w, h = size(data)
        x, y = place(data, w, h)
        always = not hideable(data)
        controls.append(Control(data["name"], "button", data, (x, y, x + int(w), y + int(h)),
                                data["displayInGame"] or always, data["displayInMenu"] or always))
    for drawer in layout["mDrawerDataList"]:
        props = drawer["properties"]
        w, h = size(props)
        x, y = place(props, w, h)
        d = Control(props["name"], "drawer", props, (x, y, x + int(w), y + int(h)),
                    props["displayInGame"], props["displayInMenu"])
        controls.append(d)
        for i, sub in enumerate(drawer["buttonProperties"]):
            sw, sh = size(sub)
            sx, sy = place(sub, sw, sh)  # evaluated once in the constructor, must not throw
            if drawer["orientation"] != "FREE":
                sw, sh = w, h
                step = i + 1
                if drawer["orientation"] == "LEFT":
                    sx, sy = x - (w + margin) * step, y
                elif drawer["orientation"] == "RIGHT":
                    sx, sy = x + (w + margin) * step, y
                elif drawer["orientation"] == "UP":
                    sx, sy = x, y - (h + margin) * step
                else:
                    sx, sy = x, y + (h + margin) * step
            controls.append(Control(sub["name"], "sub", sub, (sx, sy, sx + int(sw), sy + int(sh)),
                                    d.game, d.menu, parent=d))
    return controls


def label(c):
    name = c.label.replace("\n", " ")
    return "%s «%s»" % (c.kind, name) if c.kind != "sub" else "sub «%s» of «%s»" % (name, c.parent.label)


def check_geometry(layout, printable, verbose):
    worst = {}
    for scale in BUTTON_SCALES:
        for s in SCREENS:
            required = scale == 100
            problems = []
            try:
                controls = build(layout, s, scale)
            except (ValueError, ZeroDivisionError, KeyError) as e:
                error("%s at button size %d%%: an expression does not evaluate: %s" % (s, scale, e))
                continue
            game_zones, soft_zones, screen_zones, screen_soft = zones(s)
            for mode in ("game", "menu"):
                shown = [c for c in controls if getattr(c, mode) and c.kind != "sub"]
                subs = [c for c in controls if getattr(c, mode) and c.kind == "sub"]
                everything = shown + subs  # every drawer open at once
                for c in everything:
                    l, t, r, b = c.rect
                    if l < -0.5 or t < -0.5 or r > s.W + 0.5 or b > s.H + 0.5:
                        problems.append("%s leaves the screen (%s)" % (label(c), mode))
                for i, a in enumerate(everything):
                    for b in everything[i + 1:]:
                        if a.kind == "sub" and b.kind == "sub" and a.parent is b.parent:
                            continue
                        if overlap(a.rect, b.rect):
                            problems.append("%s and %s overlap (%s)" % (label(a), label(b), mode))
                zone_set = game_zones if mode == "game" else screen_zones
                for c in shown:
                    for zone, rect in zone_set.items():
                        if overlap(c.rect, rect):
                            problems.append("%s covers the %s (%s)" % (label(c), zone, mode))
                if mode == "menu" and required:
                    for c in shown:
                        for zone, rect in screen_soft.items():
                            if overlap(c.rect, rect):
                                notes.append("%s: %s covers the %s" % (s, label(c), zone))
                if mode == "game":
                    for c in shown:
                        for zone, rect in soft_zones.items():
                            if overlap(c.rect, rect) and required:
                                notes.append("%s: %s covers the %s" % (s, label(c), zone))
                    for c in subs:
                        for zone, rect in game_zones.items():
                            if not overlap(c.rect, rect):
                                continue
                            if zone == "hotbar":
                                problems.append("%s covers the %s when open" % (label(c), zone))
                            elif required:
                                notes.append("%s: %s covers the %s while its drawer is open" % (s, label(c), zone))
            if required:
                for p in problems:
                    error("%s: %s" % (s, p))
            else:
                worst.setdefault(scale, []).extend("%s: %s" % (s, p) for p in problems)
            if verbose and required:
                print("  %s: %d controls, %d problems" % (s, len(controls), len(problems)))
    for scale, problems in sorted(worst.items()):
        if problems:
            warn("button size %d%%: %d problem(s), first: %s" % (scale, len(problems), problems[0]))
        else:
            notes.append("button size %d%%: no overlap on any screen" % scale)
    # letter keys in screens
    try:
        controls = build(layout, SCREENS[0], 100)
    except (ValueError, ZeroDivisionError):
        return  # already reported
    for c in controls:
        if c.menu and any(k in printable for k in c.data["keycodes"]):
            error("%s types a character and is shown in screens (search boxes)" % label(c))


# =================================================================== schema

def check_schema(layout, raw):
    control_fields = java_fields(os.path.join(PKG, "customcontrols", "ControlData.java"))
    joystick_fields = dict(control_fields)
    joystick_fields.update(java_fields(os.path.join(PKG, "customcontrols", "ControlJoystickData.java")))
    drawer_fields = java_fields(os.path.join(PKG, "customcontrols", "ControlDrawerData.java"))
    layout_fields = java_fields(os.path.join(PKG, "customcontrols", "CustomControls.java"))
    orientations = java_enum(os.path.join(PKG, "customcontrols", "ControlDrawerData.java"), "Orientation")
    converter = read(os.path.join(PKG, "customcontrols", "LayoutConverter.java"))
    if "version == 8" not in converter:
        error("LayoutConverter no longer reads version 8 as is")

    expected_control = {"name", "keycodes", "dynamicX", "dynamicY", "width", "height", "isToggle",
                        "passThruEnabled", "isSwipeable", "displayInGame", "displayInMenu", "opacity",
                        "bgColor", "strokeColor", "strokeWidth", "cornerRadius"}
    if set(control_fields) != expected_control:
        error("ControlData fields changed: %s; review this checker" % sorted(set(control_fields) ^ expected_control))

    def check_type(where, name, java_type, value):
        base = java_type.replace(" ", "")
        if base == "String":
            ok = isinstance(value, str)
        elif base == "boolean":
            ok = isinstance(value, bool)
        elif base in ("float", "double"):
            ok = isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
        elif base == "int":
            ok = isinstance(value, int) and not isinstance(value, bool) and -2 ** 31 <= value < 2 ** 31
        elif base == "int[]":
            ok = isinstance(value, list) and all(isinstance(v, int) and not isinstance(v, bool) for v in value)
        else:
            ok = None
        if ok is False:
            error("%s: field %s is not a Java %s: %r" % (where, name, java_type, value))
        return ok

    def check_object(where, obj, fields):
        if not isinstance(obj, dict):
            error("%s is not an object" % where)
            return
        missing = sorted(set(fields) - set(obj))
        unknown = sorted(set(obj) - set(fields))
        if missing:
            error("%s: missing fields %s (Gson would leave the constructor defaults)" % (where, missing))
        if unknown:
            error("%s: fields unknown to the Java model %s" % (where, unknown))
        for name, java_type in fields.items():
            if name in obj:
                check_type(where, name, java_type, obj[name])

    def check_control(where, obj, fields):
        check_object(where, obj, fields)
        if not isinstance(obj, dict):
            return
        def number(name, default):
            value = obj.get(name, default)
            return value if isinstance(value, (int, float)) and not isinstance(value, bool) else default

        if not isinstance(obj.get("keycodes"), list) or len(obj["keycodes"]) != 4:
            error("%s: keycodes must hold 4 codes (ControlData.inflateKeycodeArray)" % where)
        if not (0 <= number("opacity", 1) <= 1):
            error("%s: opacity out of 0..1" % where)
        if not (0 <= number("cornerRadius", 0) <= 100):
            error("%s: cornerRadius out of 0..100" % where)
        if number("width", 1) <= 0 or number("height", 1) <= 0:
            error("%s: zero size (LayoutSanitizer drops it)" % where)
        for axis in ("dynamicX", "dynamicY"):
            if "Infinity" in str(obj.get(axis, "")):
                error("%s: %s contains Infinity (LayoutSanitizer drops it)" % (where, axis))

    check_object("layout", layout, layout_fields)
    if layout.get("version") != 8:
        error("layout version is %r, not 8" % layout.get("version"))
    for i, b in enumerate(layout.get("mControlDataList", [])):
        check_control("button %d «%s»" % (i, b.get("name")), b, control_fields)
    for i, j in enumerate(layout.get("mJoystickDataList", [])):
        check_control("joystick %d" % i, j, joystick_fields)
        if j.get("width") != j.get("height"):
            error("joystick %d is not square (LayoutConverter.convertV6_7Layout)" % i)
    for i, d in enumerate(layout.get("mDrawerDataList", [])):
        check_object("drawer %d" % i, d, drawer_fields)
        if d.get("orientation") not in orientations:
            error("drawer %d: orientation %r is not one of %s" % (i, d.get("orientation"), orientations))
        check_control("drawer %d properties" % i, d.get("properties"), control_fields)
        for k, sub in enumerate(d.get("buttonProperties", [])):
            check_control("drawer %d sub-button %d «%s»" % (i, k, sub.get("name")), sub, control_fields)
    # duplicate keys would be silently merged by Gson
    json.loads(raw, object_pairs_hook=no_duplicates)


def no_duplicates(pairs):
    seen = set()
    for k, _ in pairs:
        if k in seen:
            error("duplicate JSON key %r" % k)
        seen.add(k)
    return dict(pairs)


def all_controls(layout):
    for b in layout["mControlDataList"]:
        yield b
    for j in layout["mJoystickDataList"]:
        yield j
    for d in layout["mDrawerDataList"]:
        yield d["properties"]
        for sub in d["buttonProperties"]:
            yield sub


def check_keycodes(layout, glfw, special):
    for c in all_controls(layout):
        for k in c["keycodes"]:
            if k != 0 and k not in glfw and k not in special:
                error("«%s»: key code %d exists neither in LwjglGlfwKeycode nor in ControlData.SPECIALBTN_*"
                      % (c["name"], k))


# =================================================================== key bindings

# In-game key mappings that are not in the pack's keybindings.txt, with their mod default
# (bytecode of the jars, elymon-android-notes/controls.md 3.1, and the NeoForge registry list
# of options.txt). Only mods Android keeps are listed.
MOD_DEFAULTS = {
    "key.cobblemon.ridingfreelook": "key.keyboard.left.alt",
    "key.elythera.menu": "key.keyboard.right.shift",
    "key.elythera.zoom": "key.keyboard.c",
    "key.elymon.menu": "key.keyboard.j",
    "key.cobblemon_smartphone.open": "key.keyboard.k",
    "key.cobblemon_smartphone.scanner": "key.keyboard.c",
    "key.petyourcobblemon.toggleinteractmode": "key.keyboard.g",
    "key.push_to_talk": "key.keyboard.unknown",
    "key.whisper": "key.keyboard.unknown",
    "key.mute_microphone": "key.keyboard.m",
    "key.disable_voice_chat": "key.keyboard.n",
    "key.hide_icons": "key.keyboard.h",
    "key.voice_chat": "key.keyboard.v",
    "key.voice_chat_settings": "key.keyboard.unknown",
    "key.voice_chat_group": "key.keyboard.g",
    "key.voice_chat_toggle_recording": "key.keyboard.unknown",
    "key.voice_chat_adjust_volumes": "key.keyboard.unknown",
    "key.ftbchunks.map": "key.keyboard.m",
    "key.ftbchunks.minimap.zoomIn": "key.keyboard.equal",
    "key.ftbchunks.minimap.zoomOut": "key.keyboard.minus",
    "accessories.key.open_accessories_screen": "key.keyboard.h",
    "key.sophisticatedbackpacks.open_backpack": "key.keyboard.b",
    "key.sophisticatedbackpacks.inventory_interaction": "key.keyboard.c",
    "key.sophisticatedbackpacks.toggle_upgrade_1": "key.keyboard.z:ALT",
    "key.sophisticatedbackpacks.toggle_upgrade_2": "key.keyboard.x:ALT",
    "justzoom.keybinds.keybind.zoom": "key.keyboard.z",
    "key.pokebike.bell": "key.keyboard.i",
    "key.pokebike.headlight": "key.keyboard.o",
    "key.pokebike.open_style": "key.keyboard.p",
    "key.cinematic_respawn.skip_cinematic": "key.keyboard.space",
    "key.jade.config": "key.keyboard.keypad.0",
    "key.jade.show_overlay": "key.keyboard.keypad.1",
    "key.jade.toggle_liquid": "key.keyboard.keypad.2",
    "key.jade.show_recipes": "key.keyboard.keypad.3",
    "key.jade.show_uses": "key.keyboard.keypad.4",
    "key.jade.narrate": "key.keyboard.keypad.5",
    "key.jade.show_details": "key.keyboard.left.shift",
}
# Mappings that only act inside screens or tooltips (GUI conflict context or checked by the
# mod against the open screen): they never fire together with the in-game keys.
SCREEN_ONLY = ("key.jei.", "key.craftingtweaks.", "key.ftbquests.gui", "key.sophisticatedcore.",
               "key.better_tooltips.", "gui.xaero_quick_confirm")
# Mods the Android policy removes, and entries of the pack file for mods it does not have.
ABSENT = ("iris.keybind.", "key.cobblemon_vocalized.", "key.presencefootsteps.", "key.craftpresence.")
# Carry On registers a ConflictFreeKeyMapping on Left Shift.

# key code sent by the layout (or hard-coded in the joystick/hotbar) -> expected mapping
EXPECTED = {
    87: "key.forward", 65: "key.left", 83: "key.back", 68: "key.right",   # joystick
    341: "key.sprint",                                                  # joystick forward lock, Courir
    49: "key.hotbar.1", 50: "key.hotbar.2", 51: "key.hotbar.3", 52: "key.hotbar.4", 53: "key.hotbar.5",
    54: "key.hotbar.6", 55: "key.hotbar.7", 56: "key.hotbar.8", 57: "key.hotbar.9",
    70: "key.swapOffhand",                                              # hotbar double tap
    81: "key.drop",                                                     # hotbar hold, Jeter
    32: "key.jump", 340: "key.sneak", -3: "key.attack", -4: "key.use",
    69: "key.inventory", 84: "key.chat", 258: "key.playerlist", 294: "key.togglePerspective",
    82: "key.cobblemon.throwpartypokemon", 264: "key.cobblemon.downshiftparty",
    265: "key.cobblemon.upshiftparty", 77: "key.cobblemon.summary", 79: "key.cobblemon.hideparty",
    342: "key.cobblemon.ridingfreelook", 74: "key.elymon.menu",
    75: "key.cobblemon_smartphone.open", 88: "key.cobblemon_smartphone.scanner",
    71: "key.petyourcobblemon.toggleinteractmode",
    78: "gui.xaero_open_map", 85: "gui.xaero_waypoints_key", 295: "gui.xaero_new_waypoint",
    90: "gui.xaero_enlarge_map", 96: "key.ftbchunks.map",
    66: "key.sophisticatedbackpacks.open_backpack", 72: "accessories.key.open_accessories_screen",
    67: "key.elythera.zoom", 86: "key.voice_chat", -11: "key.push_to_talk",
    256: None,  # Escape: hard-coded pause in vanilla, no key mapping may take it in game
}
# Keys that intentionally drive more than one mapping in game.
SHARED = {
    32: {"key.cinematic_respawn.skip_cinematic"},   # only during the respawn cinematic
    340: {"key.jade.show_details"},                 # Jade details while sneaking
}
HARD_CODED = [87, 65, 83, 68, 341, 49, 50, 51, 52, 53, 54, 55, 56, 57, 70, 81]

MOUSE_NAMES = {-3: "key.mouse.left", -4: "key.mouse.right", -6: "key.mouse.middle",
               -10: "key.mouse.4", -11: "key.mouse.5"}
GLFW_NAMES = {32: "space", 39: "apostrophe", 44: "comma", 45: "minus", 46: "period", 47: "slash",
              59: "semicolon", 61: "equal", 91: "left.bracket", 92: "backslash", 93: "right.bracket",
              96: "grave.accent", 256: "escape", 257: "enter", 258: "tab", 259: "backspace",
              260: "insert", 261: "delete", 262: "right", 263: "left", 264: "down", 265: "up",
              266: "page.up", 267: "page.down", 268: "home", 269: "end", 280: "caps.lock",
              340: "left.shift", 341: "left.control", 342: "left.alt", 343: "left.win",
              344: "right.shift", 345: "right.control", 346: "right.alt", 347: "right.win",
              334: "keypad.add"}


def mc_key(code):
    """InputConstants name of what Amethyst sends for a layout key code."""
    if code in MOUSE_NAMES:
        return MOUSE_NAMES[code]
    if 65 <= code <= 90:
        return "key.keyboard." + chr(code).lower()
    if 48 <= code <= 57:
        return "key.keyboard." + chr(code)
    if 290 <= code <= 314:
        return "key.keyboard.f%d" % (code - 289)
    if 320 <= code <= 329:
        return "key.keyboard.keypad.%d" % (code - 320)
    if code in GLFW_NAMES:
        return "key.keyboard." + GLFW_NAMES[code]
    return None


KEY_LINE = re.compile(r"key_([^:]+):([^:]+)(?::(.+)?)?")  # DefaultOptions KeyMappingDefaultsHandler.KEY_PATTERN


def colon_key_set(text, values):
    """com.elythera.elymon.sync.Overlay.colonKeySet: replace "key:" lines, append the others."""
    eol = "\r\n" if "\r\n" in text else "\n"
    lines = text.split("\n")
    trailing = text.endswith("\n")
    if trailing:
        lines = lines[:-1]
    lines = [l[:-1] if l.endswith("\r") else l for l in lines]
    done = set()
    for i, line in enumerate(lines):
        colon = line.find(":")
        if colon <= 0:
            continue
        key = line[:colon]
        if key in values:
            lines[i] = key + ":" + values[key]
            done.add(key)
    for key, value in values.items():
        if key not in done:
            lines.append(key + ":" + value)
    return eol.join(lines) + (eol if trailing and lines else "")


def parse_keybindings(text):
    table = {}
    for line in text.splitlines():
        m = KEY_LINE.fullmatch(line.strip())
        if m:
            key = m.group(2) + (":" + m.group(3) if m.group(3) else "")
            table[m.group(1)] = key
    return table


def policy_overlay():
    policy = json.loads(read(POLICY))
    values = {}
    for entry in policy.get("overlays", []):
        if entry.get("path") == KEYBINDINGS_PATH:
            for op in entry.get("ops", []):
                if op.get("op") != "colonKeySet":
                    error("keybindings overlay: only colonKeySet applies to %s" % KEYBINDINGS_PATH)
                values.update(op.get("set", {}))
    seed = policy.get("seedOptions", {})
    for key in seed:
        if key.startswith("key_"):
            error("seedOptions %s: a key_ line in options.txt would stop DefaultOptions from applying"
                  " keybindings.txt to that mapping" % key)
    if seed.get("toggleCrouch") != "true":
        error("seedOptions must set toggleCrouch:true: «Accroupi» is a plain button")
    return values


def check_keybindings(layout):
    pack_raw = read(PACK_KEYBINDINGS, "rb")
    if hashlib.md5(pack_raw).hexdigest() != PACK_KEYBINDINGS_MD5:
        warn("tools/elymon/controls/pack-keybindings.txt is not the snapshot this checker knows")
    pack_text = pack_raw.decode("utf-8")
    overlay = policy_overlay()
    if not overlay:
        error("the keybindings overlay of android-policy.json is empty")
    for key, value in overlay.items():
        m = KEY_LINE.fullmatch(key + ":" + value)
        if not key.startswith("key_") or not m or not value.endswith(":") and value.count(":") != 1:
            error("overlay %s:%s is not a DefaultOptions line (key_<name>:<key>:<modifiers>)" % (key, value))
        elif not (m.group(2).startswith("key.keyboard.") or m.group(2).startswith("key.mouse.")):
            error("overlay %s: %s is not an InputConstants key name" % (key, m.group(2)))
    pack = parse_keybindings(pack_text)
    effective_file = parse_keybindings(colon_key_set(pack_text, overlay))

    # every mapping the game will have on Android, with its key after DefaultOptions
    bindings = {}
    for name, key in MOD_DEFAULTS.items():
        bindings[name] = key
    for name, key in effective_file.items():
        bindings[name] = key
    for name in list(bindings):
        if name.startswith(ABSENT):
            del bindings[name]

    in_game = {}
    for name, key in bindings.items():
        if name.startswith(SCREEN_ONLY) or key == "key.keyboard.unknown":
            continue
        in_game.setdefault(key, []).append(name)

    used = {}
    for c in all_controls(layout):
        for k in c["keycodes"]:
            if k != 0 and k >= -11 and k not in (-1, -2, -5, -7, -8, -9):
                used.setdefault(k, set()).add(c["name"].replace("\n", " "))
    for k in HARD_CODED:
        used.setdefault(k, set()).add("joystick/hotbar")
    menu_only = set()
    for c in all_controls(layout):
        if not c["displayInGame"]:
            menu_only.update(c["keycodes"])
    for code, where in sorted(used.items()):
        key = mc_key(code)
        expected = EXPECTED.get(code, "?")
        if expected == "?":
            error("key %d (%s) used by %s has no expected action in this checker" % (code, key, sorted(where)))
            continue
        actions = set(in_game.get(key, []))
        # Right-click and Shift of the screen buttons act on screens only.
        if expected is None:
            if actions:
                error("%s (%s) must stay free in game but drives %s" % (key, sorted(where), sorted(actions)))
            continue
        allowed = {expected} | SHARED.get(code, set())
        if expected not in actions:
            error("%s (%s) should drive %s but drives %s" % (key, sorted(where), expected, sorted(actions) or "nothing"))
        extra = actions - allowed
        if extra:
            error("%s (%s) also drives %s in game" % (key, sorted(where), sorted(extra)))
        # a key held with Alt ("Vue libre" toggles Alt) would also fire ALT-modifier mappings
        alt = [n for n, v in bindings.items() if v == key + ":ALT" and not n.startswith(SCREEN_ONLY)]
        if alt:
            error("%s: %s fire while «Vue libre» holds Alt" % (key, alt))
    notes.append("key bindings: %d keys used, each drives its expected action in game" % len(used))

    # history for the options.txt migration (ElymonKeybindings)
    try:
        history = json.loads(read(KEY_HISTORY))
    except (OSError, ValueError) as e:
        error("assets/elymon/controls-keybindings.json unreadable: %s" % e)
        return
    previous = history.get("previous", {})
    for key, value in overlay.items():
        name = key[len("key_"):]
        desired = options_value(value)
        baseline = pack.get(name, MOD_DEFAULTS.get(name))
        known = previous.get(name)
        if known is None:
            error("controls-keybindings.json: no history for %s" % name)
            continue
        if baseline is not None and baseline != desired and baseline not in known:
            error("controls-keybindings.json: %s must list %s, what earlier installs have" % (name, baseline))
        if desired in known:
            error("controls-keybindings.json: %s lists its own new value %s" % (name, desired))
    for name in previous:
        if "key_" + name not in overlay:
            error("controls-keybindings.json: %s is not in the overlay" % name)


def options_value(keybindings_value):
    """keybindings.txt "key.keyboard.x:" or "key.keyboard.k:CONTROL" -> options.txt value."""
    key, _, modifiers = keybindings_value.partition(":")
    return key + (":" + modifiers if modifiers else "")


# =================================================================== files

def check_files(raw):
    if not os.path.isfile(DOC_COPY) or read(DOC_COPY, "rb") != raw:
        error("docs/elymon/controls/elymon-default.json differs from assets/default.json"
              " (run tools/elymon/controls/make_layout.py)")
    digest = hashlib.sha256(raw).hexdigest()
    try:
        java = read(ELYMON_CONTROLS)
    except OSError:
        error("ElymonControls.java not found")
        return
    entries = re.findall(r'\{\s*"([^"]+)"\s*,\s*"([0-9a-f]{64})"\s*\}', java)
    if not entries:
        error("ElymonControls.LAYOUTS not found")
        return
    if entries[-1][1] != digest:
        error("assets/default.json has SHA-256 %s but the last entry of ElymonControls.LAYOUTS is %s %s:"
              " add a new version" % (digest, entries[-1][0], entries[-1][1]))
    versions = [v for v, _ in entries]
    if len(set(versions)) != len(versions):
        error("ElymonControls.LAYOUTS repeats a version")
    notes.append("assets/default.json: layout version %s, SHA-256 %s" % (entries[-1][0], digest))


# =================================================================== labels

ROBOTO = ["/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Regular.ttf",
          "/usr/share/fonts/truetype/roboto/hinted/Roboto-Regular.ttf",
          "/usr/share/fonts/truetype/roboto/Roboto-Regular.ttf"]
# Roboto has no ▲ ▼; Android falls back to Noto Sans Symbols, the preview to DejaVu Sans
SYMBOLS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]


def check_labels(layout):
    """ControlButton draws its name at 14 sp, in capitals (buttonAllCaps defaults to true),
    with 4 px of padding. A label wider than its button breaks inside the word. Height of a
    TextView with font padding, Roboto metrics: yMax 1.056 em + |yMin| 0.271 em for one line,
    plus 1.172 em per extra line. The text is not clipped by a round background, but it
    should stay inside the circle at cap height. Measured with Roboto on this host: a warning,
    since the phone's font (One UI, MIUI...) differs a little."""
    font_path = next((p for p in ROBOTO if os.path.isfile(p)), None)
    try:
        from PIL import ImageFont
    except ImportError:
        font_path = None
    if font_path is None:
        notes.append("labels not measured (needs Pillow and Roboto-Regular.ttf)")
        return
    font = ImageFont.truetype(font_path, 140)  # 14 sp at 10 px per dp
    padding = 8 / 2.625  # 4 px on each side at the most common density
    checked = 0
    for c in all_controls_with_sizes(layout):
        name, w, h, round_button = c
        if not name.strip():
            continue
        lines = name.upper().split("\n")
        text_w = max(font.getlength(line) for line in lines) / 10
        text_h = 14 * (1.056 + 0.271 + 1.172 * (len(lines) - 1))
        inner_w = w - padding
        if round_button:
            # capitals of the outer line reach 0.711 em / 2 + half the extra lines from the centre
            r = w / 2
            half = 14 * (0.711 / 2 + 1.172 * (len(lines) - 1) / 2)
            inner_w = 2 * math.sqrt(max(r * r - half * half, 0))
        if text_w > inner_w or text_h > h - padding + 0.5:
            warn("label «%s» (%.0f x %.0f dp) does not fit its %.0f x %.0f dp button"
                 % (name.replace("\n", " / "), text_w, text_h, w, h))
        checked += 1
    notes.append("%d labels measured with %s" % (checked, os.path.basename(font_path)))


def all_controls_with_sizes(layout):
    for b in layout["mControlDataList"]:
        yield b["name"], b["width"], b["height"], b["cornerRadius"] >= 100
    for d in layout["mDrawerDataList"]:
        p = d["properties"]
        yield p["name"], p["width"], p["height"], p["cornerRadius"] >= 100
        for sub in d["buttonProperties"]:
            if d["orientation"] == "FREE":
                yield sub["name"], sub["width"], sub["height"], sub["cornerRadius"] >= 100
            else:
                yield sub["name"], p["width"], p["height"], p["cornerRadius"] >= 100


# =================================================================== dump for the exp4j cross-check

def dump(layout, path):
    """One line per expression and screen: what this checker computed, for
    ControlsHostTests to recompute with the real exp4j (tools/elymon/test-controls.sh)."""
    lines = []
    for s in SCREENS:
        for scale in BUTTON_SCALES:
            margin = int(2 * s.d)
            groups = [("mJoystickDataList", layout["mJoystickDataList"]),
                      ("mControlDataList", layout["mControlDataList"])]
            entries = []
            for list_name, items in groups:
                for i, data in enumerate(items):
                    entries.append(("%s/%d" % (list_name, i), data))
            for i, drawer in enumerate(layout["mDrawerDataList"]):
                entries.append(("mDrawerDataList/%d/properties" % i, drawer["properties"]))
                for k, sub in enumerate(drawer["buttonProperties"]):
                    entries.append(("mDrawerDataList/%d/buttonProperties/%d" % (i, k), sub))
            for where, data in entries:
                w = data["width"] * s.d / layout["scaledAt"] * scale
                h = data["height"] * s.d / layout["scaledAt"] * scale
                variables = {
                    "top": "0", "left": "0",
                    "right": jfloat(s.W - w), "bottom": jfloat(s.H - h),
                    "width": jfloat(w), "height": jfloat(h),
                    "screen_width": str(s.W), "screen_height": str(s.H),
                    "margin": str(margin), "preferred_scale": jfloat(scale),
                }
                for axis in ("dynamicX", "dynamicY"):
                    value = evaluate(data[axis], variables, s.d)
                    lines.append("\t".join([str(s.W), str(s.H), repr(s.d), str(scale), where, axis, repr(value)]))
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    notes.append("%d evaluated expressions written to %s" % (len(lines), path))


# =================================================================== preview

def preview(layout, directory):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        warn("--preview needs Pillow")
        return
    os.makedirs(directory, exist_ok=True)
    font_path = next((p for p in ROBOTO if os.path.isfile(p)), None)
    for s in SCREENS:
        label_font = zone_font = symbol_font = None
        if font_path:
            from PIL import ImageFont
            label_font = ImageFont.truetype(font_path, int(round(14 * s.d)))
            zone_font = ImageFont.truetype(font_path, int(round(10 * s.d)))
            symbols = next((p for p in SYMBOLS if os.path.isfile(p)), None)
            symbol_font = ImageFont.truetype(symbols, int(round(14 * s.d))) if symbols else label_font
        controls = build(layout, s, 100)
        game_zones, soft_zones, screen_zones, screen_soft = zones(s)
        for mode, with_subs in (("game", False), ("game", True), ("menu", False)):
            img = Image.new("RGB", (s.W, s.H), (58, 84, 58) if mode == "game" else (40, 40, 60))
            draw = ImageDraw.Draw(img, "RGBA")
            zone_set = dict(game_zones, **soft_zones) if mode == "game" else dict(screen_zones, **screen_soft)
            for name, (l, t, r, b) in zone_set.items():
                colour = (255, 200, 0, 70) if name in soft_zones or name in screen_soft else (255, 60, 60, 80)
                draw.rectangle([l, t, r, b], fill=colour)
                draw.text((l + 4, t + 4), name, fill=(255, 255, 255, 200), font=zone_font)
            for c in controls:
                if not getattr(c, mode) or (c.kind == "sub" and not with_subs):
                    continue
                l, t, r, b = c.rect
                fill = c.data["bgColor"] & 0xFFFFFFFF
                rgba = ((fill >> 16) & 255, (fill >> 8) & 255, fill & 255, max(90, (fill >> 24) & 255))
                outline = (255, 255, 255, 255) if c.kind != "sub" else (255, 255, 0, 255)
                if c.kind == "joystick":
                    draw.ellipse([l, t, r, b], fill=rgba, outline=outline)
                else:
                    draw.rounded_rectangle([l, t, r, b], radius=min(r - l, b - t) * c.data["cornerRadius"] / 200,
                                           fill=rgba, outline=outline, width=2)
                text = c.label.upper() if c.kind != "joystick" else ""
                font = symbol_font if any(ord(ch) > 0x2000 for ch in text) else label_font
                draw.multiline_text(((l + r) / 2, (t + b) / 2), text, fill=(255, 255, 255, 255),
                                    font=font, anchor="mm", align="center")
            state = mode + ("-drawers-open" if with_subs else "")
            draw.text((10, s.H - 12 * s.d), "%s %s" % (s, state), fill=(255, 255, 255, 255), font=zone_font)
            name = "%s-%dx%d-%g-%s.png" % (s.label.replace(":", "x"), s.W, s.H, s.d, state)
            img.save(os.path.join(directory, name))
    notes.append("previews written to %s" % directory)


# =================================================================== main

def main(argv):
    verbose = "--verbose" in argv
    preview_dir = None
    if "--preview" in argv:
        preview_dir = argv[argv.index("--preview") + 1]
    dump_file = None
    if "--dump" in argv:
        dump_file = argv[argv.index("--dump") + 1]
    raw = read(LAYOUT, "rb")
    try:
        layout = json.loads(raw.decode("utf-8"))
    except ValueError as e:
        print("FAIL assets/default.json is not valid JSON: %s" % e)
        return 1
    glfw, printable = glfw_keycodes()
    special = special_keycodes()
    check_schema(layout, raw.decode("utf-8"))
    if not errors:
        check_keycodes(layout, glfw, special)
        check_geometry(layout, printable, verbose)
        check_keybindings(layout)
        check_labels(layout)
        if preview_dir and not errors:
            preview(layout, preview_dir)
        if dump_file and not errors:
            dump(layout, dump_file)
    check_files(raw)

    screens = sorted({(s.W, s.H) for s in SCREENS})
    missing = REQUIRED_RESOLUTIONS - set(screens)
    if missing:
        error("required resolutions not checked: %s" % sorted(missing))
    print("Checked %d buttons, %d drawers, %d joystick(s) on %d screens (%s) at button size %s%%"
          % (len(layout.get("mControlDataList", [])), len(layout.get("mDrawerDataList", [])),
             len(layout.get("mJoystickDataList", [])), len(SCREENS),
             ", ".join("%dx%d" % r for r in screens), "/".join(str(b) for b in BUTTON_SCALES)))
    soft = [n for n in notes if " covers the " in n]
    for n in notes if verbose else [n for n in notes if n not in soft]:
        print("note: " + n)
    if soft and not verbose:
        counts = {}
        for n in soft:
            zone = n.rsplit(" covers the ", 1)[1]
            counts[zone] = counts.get(zone, 0) + 1
        print("note: allowed overlaps (--verbose lists them): " +
              ", ".join("%s x%d" % (z, c) for z, c in sorted(counts.items())))
    for w in warnings:
        print("WARN " + w)
    for e in errors:
        print("FAIL " + e)
    print("OK" if not errors else "%d failure(s)" % len(errors))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
