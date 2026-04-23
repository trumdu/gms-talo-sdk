/// @description Клики, колёсико и перетаскивание (мышь / сенсор, device 0)
var dev = scroll_pan_dev;
var gw = display_get_gui_width();
var gh = display_get_gui_height();
var mx = device_mouse_x_to_gui(dev);
var my = device_mouse_y_to_gui(dev);

var pad = 16;
var log_h = min(260, gh * 0.34);
var btn_top = log_h + pad * 2;
var cols = 3;
var btn_gap = 8;
var btn_h = 34;
var btn_w = (gw - pad * 3) / cols;
var btn_area_bottom = gh - 56;
var btn_area_h = max(80, btn_area_bottom - btn_top);
var num_rows = ceil(array_length(btn_labels) / cols);
var content_h = num_rows * (btn_h + btn_gap);
var btn_scroll_max = max(0, content_h - btn_area_h);

var log_line_h = 20;
var log_visible_h = max(40, log_h - pad - 44);
var log_content_h = array_length(log_lines) * log_line_h;
var scroll_log_max = max(0, log_content_h - log_visible_h);

var in_log = point_in_rectangle(mx, my, pad, pad, gw - pad, log_h - pad);
var in_btn = point_in_rectangle(mx, my, pad, btn_top, gw - pad, btn_area_bottom);

if (mouse_wheel_up()) {
	if (in_log) {
		scroll_log = max(0, scroll_log - 28);
	} else if (in_btn) {
		btn_scroll = max(0, btn_scroll - (btn_h + btn_gap));
	}
}
if (mouse_wheel_down()) {
	if (in_log) {
		scroll_log = min(scroll_log_max, scroll_log + 28);
	} else if (in_btn) {
		btn_scroll = min(btn_scroll_max, btn_scroll + (btn_h + btn_gap));
	}
}

if (device_mouse_check_button_pressed(dev, mb_left)) {
	scroll_pan_moved = false;
	scroll_pan_press_x = mx;
	scroll_pan_press_y = my;
	if (in_log) {
		scroll_pan_zone = "log";
		scroll_pan_log_y0 = my;
		scroll_pan_log_scroll0 = scroll_log;
	} else if (in_btn) {
		scroll_pan_zone = "btn";
		scroll_pan_btn_y0 = my;
		scroll_pan_btn_scroll0 = btn_scroll;
	} else {
		scroll_pan_zone = "none";
	}
}

if (device_mouse_check_button(dev, mb_left) && scroll_pan_zone == "log") {
	scroll_log = clamp(scroll_pan_log_scroll0 + (my - scroll_pan_log_y0), 0, scroll_log_max);
	var d = point_distance(scroll_pan_press_x, scroll_pan_press_y, mx, my);
	if (d > scroll_pan_threshold) scroll_pan_moved = true;
}

if (device_mouse_check_button(dev, mb_left) && scroll_pan_zone == "btn") {
	btn_scroll = clamp(scroll_pan_btn_scroll0 + (my - scroll_pan_btn_y0), 0, btn_scroll_max);
	var d2 = point_distance(scroll_pan_press_x, scroll_pan_press_y, mx, my);
	if (d2 > scroll_pan_threshold) scroll_pan_moved = true;
}

if (device_mouse_check_button_released(dev, mb_left)) {
	if (scroll_pan_zone == "btn" && !scroll_pan_moved) {
		var rmx = device_mouse_x_to_gui(dev);
		var rmy = device_mouse_y_to_gui(dev);
		for (var i = 0; i < array_length(btn_labels); i++) {
			var col = i mod cols;
			var row = i div cols;
			var bx = pad + col * (btn_w + pad);
			var by = btn_top + row * (btn_h + btn_gap) - btn_scroll;
			if (by + btn_h < btn_top || by > btn_area_bottom) continue;
			if (point_in_rectangle(rmx, rmy, bx, by, bx + btn_w, by + btn_h)) {
				talo_demo_log(">> " + btn_labels[i]);
				talo_demo_run_action(i);
				break;
			}
		}
	}
	scroll_pan_zone = "none";
}

scroll_log = clamp(scroll_log, 0, scroll_log_max);
btn_scroll = clamp(btn_scroll, 0, btn_scroll_max);
