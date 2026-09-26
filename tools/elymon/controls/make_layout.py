#!/usr/bin/env python3
"""Generates Elymon's default touch layout (Amethyst custom controls, schema version 8).

Writes the same bytes to:
  app_pojavlauncher/src/main/assets/default.json   (what the APK installs as controlmap/default.json)
  docs/elymon/controls/elymon-default.json          (the reviewed copy, see docs/elymon/controls/README.md)

Usage: tools/elymon/controls/make_layout.py [--check]
  --check  exits 1 when the files on disk differ from what this script generates.

After a change: bump LAYOUT_VERSION in ElymonControls.java and add the new SHA-256 to its
history (tools/elymon/check-controls.py says which), then run tools/elymon/check-controls.py.

Positions are exp4j expressions in pixels, evaluated by ControlData.insertDynamicPos with
${screen_width}, ${screen_height}, ${right}, ${bottom}, ${width}, ${height}, ${margin} and
${preferred_scale}; px(dp) and dp(px) convert. exp4j has abs() but no min()/max(), hence
the abs() forms below, the same trick upstream's default.json uses.

The layout keeps clear of what the game draws, measured in GUI pixels of the game's
automatic GUI scale. GUI below is its upper bound, min(W / 320, H / 240) physical pixels,
whatever the resolution scale:
  - Elymon HUD pills across the top (elymon-mod HudPillsLayer: 3 + 13 px high, 16 px side
    margins), Jade's tooltip and the territory title under them in the middle;
  - Xaero's minimap in the top-right corner (about 72 x 83 GUI pixels with its text);
  - the hotbar with hearts, armour and food above it (182 x ~50 GUI pixels);
  - Cobblemon's party column on the left (PartyOverlay: 62 px wide, 6 x 30 + 5 x 4 high);
  - in screens: Cobblemon's battle tiles, actions, message log and switch panel.
tools/elymon/check-controls.py holds the exact rectangles and checks them.
"""
import json
import os
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
ASSET = os.path.join(REPO, "app_pojavlauncher", "src", "main", "assets", "default.json")
DOC_COPY = os.path.join(REPO, "docs", "elymon", "controls", "elymon-default.json")

# ----------------------------------------------------------------- expressions

W = "${screen_width}"
H = "${screen_height}"
GUI = "((%s / 320 + %s / 240 - abs(%s / 320 - %s / 240)) / 2)" % (W, H, W, H)
BIG = "100000"  # turns any positive overlap (even 0.01 px) into "all the way"


def dp(n):
    """A fixed distance in dp (margins, gaps): not scaled by the button-size setting."""
    return "px(%s)" % fmt(n)


def sz(n):
    """The size of a button authored at n dp, as scaled by the button-size setting."""
    return "(px(%s) / 100 * ${preferred_scale})" % fmt(n)


def gui(n):
    """n GUI pixels of Minecraft at its largest automatic GUI scale."""
    return "(%s * %s)" % (fmt(n), GUI)


def fmt(n):
    return str(int(n)) if float(n) == int(n) else repr(float(n))


def add(*terms):
    return "(" + " + ".join(terms) + ")"


def sub(a, *terms):
    return "(" + a + "".join(" - " + t for t in terms) + ")"


def vmax(a, b):
    return "((%s + %s + abs(%s - %s)) / 2)" % (a, b, a, b)


def vmin(a, b):
    return "((%s + %s - abs(%s - %s)) / 2)" % (a, b, a, b)


def positive(a):
    return vmax(a, "0")


def half(a):
    return "(%s / 2)" % a


# Rectangles the in-game controls avoid (see the module docstring).
HOTBAR_LEFT = sub(half(W), gui(92))
HOTBAR_RIGHT = add(half(W), gui(92))
CENTRE_LEFT = sub(half(W), gui(100))
CENTRE_RIGHT = add(half(W), gui(100))
HUD_BOTTOM = gui(17)
CENTRE_BOTTOM = gui(56)
PARTY_RIGHT = gui(62)
BATTLE_TILES_BOTTOM = gui(52)


def hotbar_lift(overlap, bottom_gap):
    """How far a bottom-anchored group must rise to leave the hotbar (the touch strip of
    HotbarView) alone: 0 when its horizontal overlap with the hotbar is not positive, else
    just above the hotbar."""
    return vmin("(%s * %s)" % (BIG, positive(sub(overlap, "0.5"))),
                positive(sub(add(gui(23), dp(4)), dp(bottom_gap))))


def below_top(room):
    """Top of the upper-left group: under the HUD pills when it fits left of the middle
    band (Jade tooltip, territory title), that is when room >= 0, else under that band."""
    clear = positive(add(room, "1"))
    return vmax(add(HUD_BOTTOM, dp(2)), sub(add(CENTRE_BOTTOM, dp(4)), "(%s * %s)" % (BIG, clear)))


# ----------------------------------------------------------------- colours (signed ARGB)

def argb(value):
    return value - (1 << 32) if value >= 1 << 31 else value


PLAIN = argb(0x4D000000)       # upstream's default
COBBLEMON = argb(0x99B71C1C)   # Cobblemon red
MAPS = argb(0x802E7D32)        # map green
MORE = argb(0x80455A64)        # blue grey
SCREENS = argb(0x661565C0)     # buttons shown only in screens
WHITE = argb(0xFFFFFFFF)

# ----------------------------------------------------------------- key codes
# GLFW codes (LwjglGlfwKeycode) and Amethyst's special codes (ControlData.SPECIALBTN_*)
KEY = {
    "NONE": 0, "SPACE": 32, "GRAVE": 96, "B": 66, "C": 67, "E": 69, "G": 71, "H": 72, "J": 74,
    "K": 75, "M": 77, "N": 78, "O": 79, "Q": 81, "R": 82, "T": 84, "U": 85, "V": 86, "X": 88,
    "Z": 90, "ESC": 256, "TAB": 258, "DOWN": 264, "UP": 265, "F5": 294, "F6": 295,
    "LSHIFT": 340, "LCTRL": 341, "LALT": 342,
    "KEYBOARD": -1, "TOGGLECTRL": -2, "MOUSE_LEFT": -3, "MOUSE_RIGHT": -4, "MENU": -9,
    "MOUSE_5": -11,
}


def button(name, key, x, y, w, h, *, game=True, menu=False, colour=PLAIN, stroke=0.0,
           radius=35.0, toggle=False, passthru=False):
    """Every field of ControlData, written out: Gson's no-argument constructor would
    otherwise fill a 50x50 'button' in the middle of the screen."""
    return {
        "name": name,
        "keycodes": [KEY[key], 0, 0, 0],
        "dynamicX": x,
        "dynamicY": y,
        "width": float(w),
        "height": float(h),
        "isToggle": toggle,
        "passThruEnabled": passthru,
        "isSwipeable": False,
        "displayInGame": game,
        "displayInMenu": menu,
        "opacity": 1.0,
        "bgColor": colour,
        "strokeColor": WHITE,
        "strokeWidth": float(stroke),
        "cornerRadius": float(radius),
    }


# ----------------------------------------------------------------- right hand
# [Résumé]
# [  ▲  ] [Lancer ] [Utiliser]
# [  ▼  ] [Frapper] [ Sauter ]
# Labels are sized for Roboto 14 sp in capitals (ControlButton, buttonAllCaps): a label wider
# than its button breaks inside the word, a second line needs about 40 dp of height.

JUMP = 72
ATTACK = 64
USE = 64
THROW = 64
PARTY_W, PARTY_H, PARTY_GAP = 60, 40, 2
EDGE = 12

jump_x = sub("${right}", dp(EDGE))
jump_y = sub("${bottom}", dp(EDGE))
attack_right = sub(W, dp(EDGE), sz(JUMP), dp(10))
attack_x = sub(attack_right, "${width}")
attack_y = sub(H, dp(EDGE), half(sz(JUMP)), half("${height}"))
use_x = sub(W, dp(EDGE), half(sz(JUMP)), half("${width}"))
use_bottom = sub(H, dp(EDGE), sz(JUMP), dp(10))
use_y = sub(use_bottom, "${height}")
throw_x = sub(attack_right, "${width}")
throw_y = sub(use_bottom, half(sz(USE)), half("${height}"))

party_right = sub(attack_right, sz(THROW), dp(8))
# a group on the right half only meets the hotbar through its left edge
party_lift = hotbar_lift(sub(HOTBAR_RIGHT, sub(party_right, sz(PARTY_W))), 16)
down_y = sub("${bottom}", dp(16), party_lift)
up_y = sub(H, dp(16), party_lift, sz(PARTY_H), dp(PARTY_GAP), "${height}")
summary_y = sub(H, dp(16), party_lift, sz(PARTY_H), dp(PARTY_GAP), sz(PARTY_H), dp(PARTY_GAP), "${height}")
party_x = sub(party_right, "${width}")

right_hand = [
    button("Sauter", "SPACE", jump_x, jump_y, JUMP, JUMP, radius=100),
    button("Frapper", "MOUSE_LEFT", attack_x, attack_y, ATTACK, ATTACK, radius=100, passthru=True),
    button("Utiliser", "MOUSE_RIGHT", use_x, use_y, USE, USE, radius=100, passthru=True),
    button("Lancer", "R", throw_x, throw_y, THROW, THROW, radius=100, colour=COBBLEMON, stroke=1.5),
    button("\u25BC", "DOWN", party_x, down_y, PARTY_W, PARTY_H, colour=COBBLEMON, stroke=1),
    button("\u25B2", "UP", party_x, up_y, PARTY_W, PARTY_H, colour=COBBLEMON, stroke=1),
    button("R\u00E9sum\u00E9", "M", party_x, summary_y, PARTY_W, PARTY_H, colour=COBBLEMON, stroke=1),
]

# ----------------------------------------------------------------- left hand
# joystick, then [Courir] over [Accroupi] on its right

STICK = 150
STICK_BOTTOM = 12
MOVE_W, MOVE_H = 72, 52
move_left = add(dp(16), sz(STICK), dp(8))
# a group on the left half only meets the hotbar through its right edge
move_lift = hotbar_lift(sub(add(move_left, sz(MOVE_W)), HOTBAR_LEFT), 16)

left_hand = [
    # Toggle crouch comes from options.txt (seedOptions toggleCrouch:true): a plain button, so
    # Shift is never left held when a screen opens (every click would become a shift-click).
    button("Accroupi", "LSHIFT", move_left, sub("${bottom}", dp(16), move_lift), MOVE_W, MOVE_H),
    button("Courir", "LCTRL", move_left,
           sub(H, dp(16), move_lift, sz(MOVE_H), dp(6), "${height}"), MOVE_W, MOVE_H),
]

joystick = {
    "name": "D\u00E9placement",
    "keycodes": [0, 0, 0, 0],
    "dynamicX": dp(16),
    "dynamicY": sub("${bottom}", dp(STICK_BOTTOM)),
    "width": float(STICK),
    "height": float(STICK),
    "isToggle": False,
    "passThruEnabled": False,
    "isSwipeable": False,
    "displayInGame": True,
    "displayInMenu": False,
    "opacity": 1.0,
    "bgColor": PLAIN,
    "strokeColor": PLAIN,
    "strokeWidth": 0.0,
    "cornerRadius": 0.0,
    "forwardLock": True,
    "absolute": False,
}

# ----------------------------------------------------------------- upper left (in game)
# Two columns ending where the middle band starts (right of the party column when there is
# room, over it otherwise), under the HUD pills. Drawers open to the right, one lane each.
# [Pause     ] [Pokémon ▸]
# [Chat      ] [Cartes  ▸]
# [Inventaire] [Plus    ▸]  <- Plus opens two rows (FREE sub-buttons)
# [Elymon    ] [Masquer  ]

COL1_W, CELL_W, CELL_H, CELL_GAP = 84, 72, 40, 2
GRID_W = "(%s + %s + %s)" % (sz(COL1_W), dp(CELL_GAP), sz(CELL_W))
LEFT_OF_BAND = sub(CENTRE_LEFT, dp(4), GRID_W)  # left edge that ends the grid at the band
grid_left = vmax(dp(8), vmin(add(PARTY_RIGHT, dp(6)), LEFT_OF_BAND))
# the grid ends left of the band unless even an 8 dp margin is too much
grid_top = below_top(sub(LEFT_OF_BAND, dp(8)))
col2_x = add(grid_left, sz(COL1_W), dp(CELL_GAP))


def row_y(row):
    if not row:
        return grid_top
    return add(grid_top, "%d * (%s + %s)" % (row, sz(CELL_H), dp(CELL_GAP)))


upper_left = [
    button("Pause", "ESC", grid_left, row_y(0), COL1_W, CELL_H),
    button("Chat", "T", grid_left, row_y(1), COL1_W, CELL_H),
    button("Inventaire", "E", grid_left, row_y(2), COL1_W, CELL_H),
    button("Elymon", "J", grid_left, row_y(3), COL1_W, CELL_H, colour=COBBLEMON, stroke=1),
    # TOGGLECTRL is never hidden (ControlInterface isHideable), so it shows in screens too:
    # never above Cobblemon's battle tiles there.
    button("Masquer", "TOGGLECTRL", col2_x, vmax(row_y(3), add(BATTLE_TILES_BOTTOM, dp(4))),
           CELL_W, CELL_H, menu=True),
]

# ----------------------------------------------------------------- drawers (in game)

SUB_PARKED = "(0.5 * %s)" % W  # evaluated once at construction, then aligned by the drawer


def drawer(name, row, colour, subs, orientation="RIGHT"):
    props = button(name, "NONE", col2_x, row_y(row), CELL_W, CELL_H, colour=colour, stroke=1.5)
    children = []
    for i, (label, key, extra) in enumerate(subs):
        if orientation == "FREE":
            # two rows of four to the right of the drawer, the same steps RIGHT would use
            line, column = divmod(i, 4)
            x = add(col2_x, "%d * (%s + ${margin})" % (column + 1, sz(CELL_W)))
            y = row_y(row + line)
        else:
            x = y = SUB_PARKED
        children.append(button(label, key, x, y, CELL_W, CELL_H, colour=colour, **extra))
    return {"properties": props, "buttonProperties": children, "orientation": orientation}


drawers = [
    drawer("Pok\u00E9mon", 0, COBBLEMON, [
        ("Cacher\n\u00E9quipe", "O", {}),
        ("Smart-\nphone", "K", {}),
        ("Scanner", "X", {}),
        # riding free look is held: a toggle keeps Alt down while riding
        ("Vue\nlibre", "LALT", {"toggle": True}),
        ("Caresser", "G", {}),
    ]),
    drawer("Cartes", 1, MAPS, [
        ("Carte", "N", {}),
        ("Rep\u00E8res", "U", {}),
        ("Nouveau\nrep\u00E8re", "F6", {}),
        ("Mini-\ncarte", "Z", {}),
        ("Terri-\ntoires", "GRAVE", {}),
    ]),
    drawer("Plus", 2, MORE, [
        ("Jeter", "Q", {}),
        ("Joueurs", "TAB", {}),
        ("Cam\u00E9ra", "F5", {}),
        ("Sac \u00E0\ndos", "B", {}),
        # push-to-talk, held while the finger is down
        ("Parler", "MOUSE_5", {}),
        ("Vocal", "V", {}),
        ("Zoom", "C", {}),
        ("Acces-\nsoires", "H", {}),
    ], orientation="FREE"),
]

# ----------------------------------------------------------------- screens only
# left edge, between Cobblemon's battle tiles and its action buttons:
# [Retour] [Menu] [Clavier]; right edge under the opponent's tiles: [Maj] [Clic droit]

SCREEN_W, SCREEN_H, SCREEN_GAP = 64, 44, 6
screen_top = add(BATTLE_TILES_BOTTOM, dp(4))


def screen_y(row):
    if not row:
        return screen_top
    return add(screen_top, "%d * (%s + %s)" % (row, sz(SCREEN_H), dp(SCREEN_GAP)))


screens = [
    button("Retour", "ESC", dp(8), screen_y(0), SCREEN_W, SCREEN_H, game=False, menu=True, colour=SCREENS),
    button("Menu", "MENU", dp(8), screen_y(1), SCREEN_W, SCREEN_H, game=False, menu=True, colour=SCREENS),
    button("Clavier", "KEYBOARD", dp(8), screen_y(2), SCREEN_W, SCREEN_H, game=False, menu=True, colour=SCREENS),
    # held, never toggles: nothing stays pressed when the screen closes
    button("Clic\ndroit", "MOUSE_RIGHT", sub("${right}", dp(8)), screen_top, SCREEN_W, SCREEN_H,
           game=False, menu=True, colour=SCREENS),
    button("Maj", "LSHIFT", sub(W, dp(8), sz(SCREEN_W), dp(SCREEN_GAP), "${width}"), screen_top,
           SCREEN_W, SCREEN_H, game=False, menu=True, colour=SCREENS),
]

LAYOUT = {
    "version": 8,
    "scaledAt": 100.0,
    "mControlDataList": right_hand + left_hand + upper_left + screens,
    "mDrawerDataList": drawers,
    "mJoystickDataList": [joystick],
}


def render():
    return (json.dumps(LAYOUT, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main(argv):
    data = render()
    if "--check" in argv:
        stale = [p for p in (ASSET, DOC_COPY) if not os.path.isfile(p) or open(p, "rb").read() != data]
        for path in stale:
            print("out of date: " + os.path.relpath(path, REPO))
        return 1 if stale else 0
    for path in (ASSET, DOC_COPY):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        print("wrote " + os.path.relpath(path, REPO) + " (%d bytes)" % len(data))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
