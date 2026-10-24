#!/usr/bin/env python3
"""Renders the crafting recipe of the photo code for the README, the docs and the website.

    python3 scripts/render_recipe.py

Draws a crafting window in the style of the game (grey window, slots, arrow, result slot, pixel title) with the
plugin's own sprites from generate_resourcepack.py, so the image contains no game textures. Writes
images/crafting_photo_code.png. Needs no third-party modules.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import generate_resourcepack as art  # noqa: E402

SCALE = 10  # image pixels per GUI pixel, like the PorticleGun recipe images

PANEL = (198, 198, 198, 255)
LIGHT = (255, 255, 255, 255)
DARK = (85, 85, 85, 255)
BLACK = (0, 0, 0, 255)
SLOT = (139, 139, 139, 255)
SLOT_DARK = (55, 55, 55, 255)
TITLE = (64, 64, 64, 255)
CLEAR = (0, 0, 0, 0)

# a few glyphs of a pixel font, enough for the title; "#" is a pixel, rows 0-7 with the baseline under row 6
GLYPHS = {
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.", "....."],
    "r": [".....", ".....", "#.##.", "##..#", "#....", "#....", "#....", "....."],
    "a": [".....", ".....", ".###.", "....#", ".####", "#...#", ".####", "....."],
    "f": ["..##", ".#..", "####", ".#..", ".#..", ".#..", ".#..", "...."],
    "t": [".#..", ".#..", "####", ".#..", ".#..", ".#..", "..##", "...."],
    "i": ["#", ".", "#", "#", "#", "#", "#", "."],
    "n": [".....", ".....", "####.", "#...#", "#...#", "#...#", "#...#", "....."],
    "g": [".....", ".....", ".####", "#...#", "#...#", ".####", "....#", "####."],
}

WIDTH, HEIGHT = 133, 78
GRID_X, GRID_Y = 8, 16  # top left of the 3x3 grid, 18 GUI pixels per slot
RESULT_X, RESULT_Y = 98, 30  # the bigger result slot, 26 GUI pixels


def window():
    img = [[CLEAR] * WIDTH for _ in range(HEIGHT)]

    def rect(x0, y0, x1, y1, color):
        for y in range(max(0, y0), min(HEIGHT, y1 + 1)):
            for x in range(max(0, x0), min(WIDTH, x1 + 1)):
                img[y][x] = color

    # the window: black outline with cut corners, white light top left, dark shade bottom right
    rect(1, 0, WIDTH - 2, HEIGHT - 1, BLACK)
    rect(0, 1, WIDTH - 1, HEIGHT - 2, BLACK)
    rect(1, 1, WIDTH - 2, HEIGHT - 2, LIGHT)
    rect(3, 3, WIDTH - 2, HEIGHT - 2, DARK)
    rect(3, 3, WIDTH - 4, HEIGHT - 4, PANEL)
    img[1][WIDTH - 2] = img[HEIGHT - 2][1] = PANEL  # rounded light/shade corners
    img[2][WIDTH - 3] = img[HEIGHT - 3][2] = PANEL

    def slot(x, y, size):
        rect(x, y, x + size - 1, y + size - 1, SLOT)
        rect(x, y, x + size - 2, y, SLOT_DARK)
        rect(x, y, x, y + size - 2, SLOT_DARK)
        rect(x + 1, y + size - 1, x + size - 1, y + size - 1, LIGHT)
        rect(x + size - 1, y + 1, x + size - 1, y + size - 1, LIGHT)

    for row in range(3):
        for column in range(3):
            slot(GRID_X + column * 18, GRID_Y + row * 18, 18)
    slot(RESULT_X, RESULT_Y, 26)

    # the arrow between grid and result
    rect(69, 41, 81, 43, SLOT)
    for i in range(8):
        rect(82 + i, 35 + i, 82 + i, 49 - i, SLOT)

    # the title
    x = 8
    for char in "Crafting":
        glyph = GLYPHS[char]
        for gy, line in enumerate(glyph):
            for gx, pixel in enumerate(line):
                if pixel == "#":
                    img[5 + gy][x + gx] = TITLE
        x += len(glyph[0]) + 1
    return img


def place(img, sprite, x, y):
    for sy, row in enumerate(sprite):
        for sx, pixel in enumerate(row):
            if pixel[3]:
                img[y + sy][x + sx] = pixel


def main():
    img = window()
    ink, empty_map = art.ink_sprite(), art.empty_map_sprite()
    recipe = [[None, ink, None], [ink, empty_map, ink], [None, ink, None]]
    for row in range(3):
        for column in range(3):
            if recipe[row][column]:
                place(img, recipe[row][column], GRID_X + column * 18 + 1, GRID_Y + row * 18 + 1)
    place(img, art.pixels(art.QR_CODE), RESULT_X + 5, RESULT_Y + 5)
    art.write(art.ROOT / "images/crafting_photo_code.png", art.png(img, SCALE))


if __name__ == "__main__":
    main()
