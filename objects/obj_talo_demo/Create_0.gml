/// @description Инициализация демо TaloSDK (см. extensions/TaloSDK/docs-main, options/extensions/TaloSDK.json)
randomize();

leaderboard_internal_name = "demo";

btn_labels = [
	"GET /v1/game-config",
	"GET /v1/game-stats",
	"GET feedback categories",
	"GET /v1/game-channels",
	"GET /v1/players/:id (self)",
	"POST /v1/players/socket-token",
	"GET leaderboard entries",
	"leaderboard +5 (add_score)",
	"POST /v1/events",
	"GET /v1/game-saves",
	"GET player-stats",
	"identify new anonymous",
];

log_lines = [];
scroll_log = 0;

function talo_demo_log(_msg) {
	var line = string(_msg);
	array_push(log_lines, line);
	var cap = 120;
	if (array_length(log_lines) > cap) {
		array_delete(log_lines, 0, array_length(log_lines) - cap);
	}
}

function talo_demo_run_action(_idx) {
	switch (_idx) {
		case 0: TaloSDK_game_config_get(); break;
		case 1: TaloSDK_game_stats_list(); break;
		case 2: TaloSDK_game_feedback_categories_get(); break;
		case 3: TaloSDK_game_channels_list("page=0"); break;
		case 4:
			var pid = TaloSDK_get_last_player_id();
			if (pid == "") talo_demo_log("Нет player id — дождитесь успешного identify.");
			else TaloSDK_players_get(pid);
			break;
		case 5: TaloSDK_players_create_socket_token(); break;
		case 6: TaloSDK_leaderboard_entries_get(leaderboard_internal_name, "page=0"); break;
		case 7: TaloSDK_leaderboard_entries_add_score(leaderboard_internal_name, 5); break;
		case 8:
			var evjson = "[{\"name\":\"talo_gm_demo\",\"props\":[{\"key\":\"client\",\"value\":\"GameMaker\"}]}]";
			TaloSDK_events_track(evjson);
			break;
		case 9: TaloSDK_game_saves_list(); break;
		case 10: TaloSDK_game_stats_player_stats(); break;
		case 11:
			var nid = "gm_" + string(get_timer()) + "_" + string(irandom(99999999));
			TaloSDK_players_identify("anonymous", nid);
			talo_demo_log("identify anonymous / " + nid);
			break;
		default: break;
	}
}

TaloSDK_init();
talo_demo_log("TaloSDK_init() — опции API в options/extensions/TaloSDK.json");
talo_demo_log("Лидерборд internal name: \"" + leaderboard_internal_name + "\" (создайте в панели Talo или измените в Create).");
if (os_type != os_android) {
	talo_demo_log("Нативные вызовы TaloSDK в этом проекте ориентированы на Android.");
}
