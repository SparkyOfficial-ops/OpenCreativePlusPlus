package com.opencreativeplus.plugin.registry

import org.bukkit.Material

/**
 * Registers all built-in [ActionDescriptor]s into [CategoryRegistry].
 * Must be called once during plugin startup, after [CategoryRegistry] is created.
 *
 * Each entry mirrors a node registered in [BuiltInNodeRegistry] so that
 * NodeSelectionGUI can display the correct icon and label per category.
 */
object BuiltInDescriptors {

    fun register(registry: CategoryRegistry) {

        // ── События игрока (PLAYER_EVENT) ─────────────────────────────────────
        registry.register(ActionDescriptor("on_join",   "При входе игрока",  Material.DIAMOND_BLOCK, NodeCategory.PLAYER_EVENT))
        registry.register(ActionDescriptor("on_damage", "При получении урона", Material.DIAMOND_ORE,  NodeCategory.PLAYER_EVENT))
        registry.register(ActionDescriptor("on_interact","При взаимодействии", Material.DIAMOND_SWORD, NodeCategory.PLAYER_EVENT))
        registry.register(ActionDescriptor("on_death",  "При смерти игрока", Material.WITHER_ROSE,   NodeCategory.PLAYER_EVENT))
        registry.register(ActionDescriptor("on_variable_change", "При изменении переменной", Material.DETECTOR_RAIL, NodeCategory.PLAYER_EVENT))

        // ── Действия игрока (PLAYER_ACTION) ───────────────────────────────────
        registry.register(ActionDescriptor("send_message",       "Отправить сообщение",    Material.PAPER,           NodeCategory.PLAYER_ACTION, listOf("message")))
        registry.register(ActionDescriptor("send_title",         "Отправить титл",         Material.BOOK,            NodeCategory.PLAYER_ACTION, listOf("title", "subtitle")))
        registry.register(ActionDescriptor("send_action_bar",    "Отправить action bar",   Material.WRITABLE_BOOK,   NodeCategory.PLAYER_ACTION, listOf("message")))
        registry.register(ActionDescriptor("send_boss_bar",      "Отправить boss bar",     Material.DRAGON_EGG,      NodeCategory.PLAYER_ACTION, listOf("message", "color", "style")))
        registry.register(ActionDescriptor("play_animation",     "Воспроизвести анимацию", Material.BLAZE_ROD,       NodeCategory.PLAYER_ACTION, listOf("animation")))
        registry.register(ActionDescriptor("teleport_player",    "Телепортировать",        Material.ENDER_PEARL,     NodeCategory.PLAYER_ACTION, listOf("location")))
        registry.register(ActionDescriptor("teleport_to_player", "Телепорт к игроку",      Material.ENDER_EYE,       NodeCategory.PLAYER_ACTION, listOf("target")))
        registry.register(ActionDescriptor("launch_player",      "Запустить игрока",       Material.FEATHER,         NodeCategory.PLAYER_ACTION, listOf("velocity")))
        registry.register(ActionDescriptor("set_player_flight",  "Управление полётом",     Material.ELYTRA,          NodeCategory.PLAYER_ACTION, listOf("flying")))
        registry.register(ActionDescriptor("apply_potion_effect","Применить эффект",       Material.POTION,          NodeCategory.PLAYER_ACTION, listOf("effect", "duration", "amplifier")))
        registry.register(ActionDescriptor("remove_potion_effect","Убрать эффект",         Material.GLASS_BOTTLE,    NodeCategory.PLAYER_ACTION, listOf("effect")))
        registry.register(ActionDescriptor("set_player_health",  "Установить здоровье",    Material.RED_DYE,         NodeCategory.PLAYER_ACTION, listOf("health")))
        registry.register(ActionDescriptor("set_player_food_level","Установить голод",     Material.COOKED_BEEF,     NodeCategory.PLAYER_ACTION, listOf("food")))
        registry.register(ActionDescriptor("give_experience",    "Дать опыт",              Material.EXPERIENCE_BOTTLE, NodeCategory.PLAYER_ACTION, listOf("amount")))
        registry.register(ActionDescriptor("set_game_mode",      "Установить режим игры",  Material.NETHER_STAR,     NodeCategory.PLAYER_ACTION, listOf("mode")))
        registry.register(ActionDescriptor("give_item",          "Выдать предмет",         Material.CHEST_MINECART,  NodeCategory.PLAYER_ACTION, listOf("item", "amount")))
        registry.register(ActionDescriptor("remove_item",        "Убрать предмет",         Material.HOPPER_MINECART, NodeCategory.PLAYER_ACTION, listOf("item", "amount")))
        registry.register(ActionDescriptor("clear_inventory",    "Очистить инвентарь",     Material.TNT_MINECART,    NodeCategory.PLAYER_ACTION))
        registry.register(ActionDescriptor("send_dialogue",      "Отправить диалог",       Material.WRITTEN_BOOK,    NodeCategory.PLAYER_ACTION, listOf("dialogue")))

        // ── Условия игрока (IF_PLAYER) ────────────────────────────────────────
        registry.register(ActionDescriptor("equals",       "Равно",              Material.COMPARATOR,       NodeCategory.IF_PLAYER, listOf("left", "right")))
        registry.register(ActionDescriptor("greater_than", "Больше чем",         Material.REPEATER,         NodeCategory.IF_PLAYER, listOf("left", "right")))
        registry.register(ActionDescriptor("less_than",    "Меньше чем",         Material.DAYLIGHT_DETECTOR,NodeCategory.IF_PLAYER, listOf("left", "right")))
        registry.register(ActionDescriptor("has_item",     "Есть предмет",       Material.ITEM_FRAME,       NodeCategory.IF_PLAYER, listOf("item")))
        registry.register(ActionDescriptor("and_condition","И (все условия)",    Material.LIME_STAINED_GLASS,NodeCategory.IF_PLAYER))
        registry.register(ActionDescriptor("or_condition", "ИЛИ (любое условие)",Material.ORANGE_STAINED_GLASS, NodeCategory.IF_PLAYER))

        // ── Установка переменной (SET_VARIABLE) ───────────────────────────────
        registry.register(ActionDescriptor("set_variable", "Установить переменную", Material.IRON_BLOCK, NodeCategory.SET_VARIABLE, listOf("name", "value", "scope")))
        registry.register(ActionDescriptor("get_variable", "Получить переменную",   Material.IRON_ORE,   NodeCategory.SET_VARIABLE, listOf("name")))

        // ── Игровые действия (GAME_ACTION) ────────────────────────────────────
        registry.register(ActionDescriptor("wait",             "Подождать",          Material.CLOCK,          NodeCategory.GAME_ACTION, listOf("duration")))
        registry.register(ActionDescriptor("set_block",        "Поставить блок",     Material.GRASS_BLOCK,    NodeCategory.GAME_ACTION, listOf("location", "material")))
        registry.register(ActionDescriptor("get_block",        "Получить блок",      Material.STONE,          NodeCategory.GAME_ACTION, listOf("location")))
        registry.register(ActionDescriptor("set_weather",      "Установить погоду",  Material.LIGHTNING_ROD,  NodeCategory.GAME_ACTION, listOf("weather")))
        registry.register(ActionDescriptor("set_time",         "Установить время",   Material.SUNFLOWER,      NodeCategory.GAME_ACTION, listOf("time")))
        registry.register(ActionDescriptor("create_explosion", "Создать взрыв",      Material.TNT,            NodeCategory.GAME_ACTION, listOf("location", "power")))
        registry.register(ActionDescriptor("spawn_particle",   "Спавн частиц",       Material.FIREWORK_ROCKET,NodeCategory.GAME_ACTION, listOf("particle", "location")))
        registry.register(ActionDescriptor("play_sound",       "Воспроизвести звук", Material.JUKEBOX,        NodeCategory.GAME_ACTION, listOf("sound", "location")))
        registry.register(ActionDescriptor("draw_line",        "Нарисовать линию",   Material.GLOWSTONE,      NodeCategory.GAME_ACTION, listOf("from", "to", "particle")))
        registry.register(ActionDescriptor("fill_region",      "Заполнить регион",   Material.SPONGE,         NodeCategory.GAME_ACTION, listOf("from", "to", "material")))
        registry.register(ActionDescriptor("copy_region",      "Скопировать регион", Material.WET_SPONGE,     NodeCategory.GAME_ACTION, listOf("from", "to")))
        registry.register(ActionDescriptor("paste_region",     "Вставить регион",    Material.STRUCTURE_BLOCK,NodeCategory.GAME_ACTION, listOf("location")))
        registry.register(ActionDescriptor("random_action",    "Случайное действие", Material.AMETHYST_SHARD, NodeCategory.GAME_ACTION, listOf("branches")))

        // ── Выбор объекта (SELECT_OBJECT) ─────────────────────────────────────
        registry.register(ActionDescriptor("select_targets",      "Выбрать цели",         Material.PURPUR_BLOCK, NodeCategory.SELECT_OBJECT, listOf("selector")))
        registry.register(ActionDescriptor("spawn_entity",        "Заспавнить сущность",  Material.ZOMBIE_HEAD,  NodeCategory.SELECT_OBJECT, listOf("type", "location")))
        registry.register(ActionDescriptor("kill_entity",         "Убить сущность",       Material.SKELETON_SKULL, NodeCategory.SELECT_OBJECT))
        registry.register(ActionDescriptor("set_entity_ai",       "Управление AI",        Material.CREEPER_HEAD, NodeCategory.SELECT_OBJECT, listOf("enabled")))
        registry.register(ActionDescriptor("set_entity_health",   "Здоровье сущности",    Material.WITHER_SKELETON_SKULL, NodeCategory.SELECT_OBJECT, listOf("health")))
        registry.register(ActionDescriptor("move_entity_to",      "Переместить сущность", Material.PLAYER_HEAD,  NodeCategory.SELECT_OBJECT, listOf("location")))
        registry.register(ActionDescriptor("get_nearby_entities", "Ближайшие сущности",   Material.DRAGON_HEAD,  NodeCategory.SELECT_OBJECT, listOf("radius")))

        // ── Операции с массивом (ARRAY_OP) ────────────────────────────────────
        registry.register(ActionDescriptor("create_list",      "Создать список",       Material.CHEST,    NodeCategory.ARRAY_OP, listOf("name")))
        registry.register(ActionDescriptor("add_to_list",      "Добавить в список",    Material.HOPPER,   NodeCategory.ARRAY_OP, listOf("list", "value")))
        registry.register(ActionDescriptor("get_list_size",    "Размер списка",        Material.OBSERVER, NodeCategory.ARRAY_OP, listOf("list")))
        registry.register(ActionDescriptor("get_list_element", "Элемент списка",       Material.BARREL,   NodeCategory.ARRAY_OP, listOf("list", "index")))
        registry.register(ActionDescriptor("filter_list",      "Фильтровать список",   Material.DROPPER,  NodeCategory.ARRAY_OP, listOf("list", "condition")))

        // ── Цикл (LOOP) ───────────────────────────────────────────────────────
        registry.register(ActionDescriptor("foreach", "Для каждого",  Material.CHAIN,                    NodeCategory.LOOP, listOf("list", "variable")))
        registry.register(ActionDescriptor("repeat",  "Повторить",    Material.REPEATING_COMMAND_BLOCK,  NodeCategory.LOOP, listOf("times")))

        // ── Функция (FUNCTION) ────────────────────────────────────────────────
        registry.register(ActionDescriptor("gui_designer", "Редактор GUI",   Material.CRAFTING_TABLE, NodeCategory.FUNCTION))
        registry.register(ActionDescriptor("open_menu",    "Открыть меню",   Material.ENDER_CHEST,    NodeCategory.FUNCTION, listOf("menu", "target")))

        // ── Скорборды (GAME_ACTION — scoreboard) ──────────────────────────────
        registry.register(ActionDescriptor("create_scoreboard",   "Создать скорборд",  Material.OAK_SIGN,    NodeCategory.GAME_ACTION, listOf("title")))
        registry.register(ActionDescriptor("set_scoreboard_line", "Строка скорборда",  Material.BIRCH_SIGN,  NodeCategory.GAME_ACTION, listOf("line", "text")))
        registry.register(ActionDescriptor("show_scoreboard",     "Показать скорборд", Material.SPRUCE_SIGN, NodeCategory.GAME_ACTION))
        registry.register(ActionDescriptor("hide_scoreboard",     "Скрыть скорборд",   Material.JUNGLE_SIGN, NodeCategory.GAME_ACTION))
    }
}
