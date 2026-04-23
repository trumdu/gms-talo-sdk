/// @description Клики по кнопкам (GUI)
var gw = display_get_gui_width();
var gh = display_get_gui_height();
var mx = device_mouse_x_to_gui(0);
var my = device_mouse_y_to_gui(0);

var log_h = min(520, gh * 0.42);
var pad = 16;
var cols = 2;
var btn_w = (gw - pad * 3) / cols;
var btn_h = 56;
var btn_top = log_h + pad * 2;

if (mouse_wheel_up()) scroll_log = max(0, scroll_log - 28);
if (mouse_wheel_down()) scroll_log += 28;

if (mouse_check_button_pressed(mb_left)) {
	if (mx >= pad && mx <= gw - pad && my >= pad && my <= log_h - pad) {
		// клик по области лога — только скролл, уже обработан колёсиком
	} else {
		for (var i = 0; i < array_length(btn_labels); i++) {
			var col = i mod cols;
			var row = i div cols;
			var bx = pad + col * (btn_w + pad);
			var by = btn_top + row * (btn_h + 10);
			if (point_in_rectangle(mx, my, bx, by, bx + btn_w, by + btn_h)) {
				talo_demo_log(">> " + btn_labels[i]);
				talo_demo_run_action(i);
				break;
			}
		}
	}
}
