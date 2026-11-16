// Phone Light Gun — parametric pistol grip (OpenSCAD)
//
// The phone lies in a cradle with its TOP EDGE pointing toward the muzzle. In phone
// (Android sensor) coordinates the barrel direction is therefore +Y for every mount
// style; only the origin offset t_P changes. The script echoes the matching profile
// JSON so you can save it to grip/assets/grip_profiles/<name>.json.
//
// Render:  openscad -o grip.stl -D 'mount="top"' lightgun_grip.scad

/* [Phone] */
phone_len   = 160.0;  // along the long edge (portrait height), mm
phone_wid   = 75.0;   // portrait width, mm
phone_thk   = 9.0;    // with case, mm
corner_r    = 8.0;
// camera module centre, measured on the BACK from the top-left corner (looking at the back)
cam_from_top  = 12.0;
cam_from_left = 20.0;
cam_window_w  = 34.0; // opening size; keep generous so tracking survives
cam_window_h  = 30.0;
// volume-down button centre: distance from the top edge, on which side
vol_from_top  = 45.0;
vol_side      = "right"; // "right" or "left" when looking at the screen

/* [Grip] */
mount       = "top";  // "top": screen up, camera sees the floor. "side": screen faces the player's left
wall        = 2.4;
lip         = 3.0;    // retaining lip over the screen edge
clearance   = 0.4;
handle_len  = 95;
handle_w    = 30;
handle_d    = 34;
handle_angle = 18;    // rake, degrees
handle_from_rear = 30; // handle position from the rear (bottom edge of the phone)
lever       = true;   // printed flexure lever over the volume-down button (trigger)

$fn = 48;

L = phone_len + 2 * clearance;
W = phone_wid + 2 * clearance;
T = phone_thk + clearance;

module rounded_box(l, w, h, r) {
    hull() for (x = [r, l - r], y = [r, w - r]) translate([x, y, 0]) cylinder(r = r, h = h);
}

// Cradle in a local frame: X = toward muzzle (phone top), Y = phone width, Z = up out of the screen.
module cradle() {
    difference() {
        rounded_box(L + 2 * wall, W + 2 * wall, T + wall, corner_r + wall);
        // phone pocket
        translate([wall, wall, wall]) rounded_box(L, W, T + 1, corner_r);
        // screen opening (leave lip)
        translate([wall + lip, wall + lip, wall + 1]) rounded_box(L - 2 * lip, W - 2 * lip, T + 1, corner_r);
        // camera window through the floor (back of phone). Back-view left == screen-view right.
        translate([wall + L - cam_from_top - cam_window_h / 2,
                   wall + W - cam_from_left - cam_window_w / 2, -1])
            cube([cam_window_h, cam_window_w, wall + 2]);
        // slide-in slot at the rear (phone bottom) for inserting the phone
        translate([-1, wall + lip, wall]) cube([wall + 2, W - 2 * lip, T + 1]);
        // volume button access
        translate([wall + L - vol_from_top - 12, vol_side == "right" ? -1 : W + wall - 1, wall + 1])
            cube([24, wall + 2, T - 2]);
    }
    if (lever) trigger_lever();
}

// Flexure lever: hinged at the front, pressed by the index finger, pushes the volume key.
module trigger_lever() {
    side_y = vol_side == "right" ? -6 : W + 2 * wall + 2;
    translate([wall + L - vol_from_top - 10, side_y, wall + T / 2 - 3]) {
        cube([30, 4, 6]);                       // lever arm
        translate([-2, vol_side == "right" ? 4 : -2, 0]) cube([4, 2 + wall, 6]); // hinge web to cradle
        translate([8, vol_side == "right" ? 4 : -3, 1]) cube([5, 3, 4]); // plunger
    }
}

module handle() {
    translate([handle_from_rear, (W + 2 * wall - handle_w) / 2, 0])
        rotate([0, 180 - handle_angle, 0])
            hull() {
                translate([0, 0, 0]) rounded_box(handle_d, handle_w, 1, 8);
                translate([0, 0, handle_len]) rounded_box(handle_d * 0.9, handle_w * 0.95, 1, 8);
            }
}

module grip() {
    if (mount == "top") {
        cradle();
        handle();
    } else {
        // roll the cradle so the screen faces the player's left; camera sees the right-hand room
        translate([0, 0, W + 2 * wall]) rotate([-90, 0, 0]) cradle();
        translate([0, -(T + wall) / 2 + (W + 2 * wall) / 2 - handle_w / 2, 0]) handle();
    }
}

grip();

// ---------------------------------------------------------------------------- profile
// Ray origin offset t_P (metres) from the phone centre to the sight line. The sight line
// sits above the phone for "top" (eye looks along the screen) and on the phone centre
// line for "side". Only the component perpendicular to +Y matters.
t_top  = [0, 0, (T / 2 + 6) / 1000];
t_side = [0, 0, 0];
echo(str("PROFILE {\"name\": \"printed-", mount, "\", \"description\": \"lightgun_grip.scad mount=", mount,
    "\", \"barrel\": {\"b\": [0, 1, 0], \"t\": ", mount == "top" ? t_top : t_side, "}}"));
