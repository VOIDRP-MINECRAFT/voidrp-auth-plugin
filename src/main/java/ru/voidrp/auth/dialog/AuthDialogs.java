package ru.voidrp.auth.dialog;

import java.util.List;
import java.util.function.Consumer;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import ru.voidrp.auth.config.AuthConfig;

/**
 * The windows the player actually sees. Built with Paper's dialog API, which draws them
 * natively on the client — no resource pack and no mod, so a vanilla or third-party
 * client gets the same screen our launcher users would never see.
 *
 * <p>Every window is unclosable: ``canCloseWithEscape(false)`` plus a single action
 * button, so the only ways out are submitting it or disconnecting.
 */
public final class AuthDialogs {

    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_PASSWORD_REPEAT = "password_repeat";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_OFFER = "offer";
    public static final String FIELD_PERSONAL_DATA = "personal_data";
    public static final String FIELD_DIST_PROFILE = "dist_profile";
    public static final String FIELD_DIST_MAP = "dist_map";
    public static final String FIELD_DIST_PURCHASES = "dist_purchases";

    private final AuthConfig config;

    public AuthDialogs(AuthConfig config) {
        this.config = config;
    }

    /** Password prompt for a nickname the site already knows. */
    public Dialog login(String nickname, String error, Consumer<DialogResponseView> onSubmit) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Вход на VoidRP"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(bodyWithError(List.of(
                                DialogBody.plainMessage(Component.text(
                                        "Аккаунт " + nickname + " уже зарегистрирован.", NamedTextColor.GRAY), 320),
                                DialogBody.plainMessage(Component.text(
                                        "Введите пароль от аккаунта VoidRP.", NamedTextColor.GRAY), 320)), error))
                        .inputs(List.of(password(FIELD_PASSWORD, "Пароль")))
                        .build())
                .type(DialogType.multiAction(List.of(
                                submit("Войти", onSubmit),
                                link("Забыли пароль?", config.resetPasswordUrl())))
                        .columns(2)
                        .build()));
    }

    /** Full registration, mirroring the fields of the site's form. */
    public Dialog register(String nickname, String error, Consumer<DialogResponseView> onSubmit) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Регистрация на VoidRP"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(bodyWithError(List.of(
                                DialogBody.plainMessage(Component.text(
                                        "Аккаунт будет создан на ник " + nickname + ".", NamedTextColor.GRAY), 320),
                                DialogBody.plainMessage(Component.text(
                                        "С ним же вы зайдёте на сайт void-rp.ru.", NamedTextColor.GRAY), 320),
                                DialogBody.plainMessage(Component.text(
                                        "Почта только российская: mail.ru, yandex.ru, bk.ru, rambler.ru и др.",
                                        NamedTextColor.DARK_GRAY), 320)), error))
                        .inputs(List.of(
                                DialogInput.text(FIELD_EMAIL, Component.text("Почта"))
                                        .width(300)
                                        .maxLength(320)
                                        .build(),
                                password(FIELD_PASSWORD, "Пароль (от 8 символов)"),
                                password(FIELD_PASSWORD_REPEAT, "Повторите пароль"),
                                checkbox(FIELD_OFFER, "Принимаю условия оферты", false),
                                checkbox(FIELD_PERSONAL_DATA, "Согласен на обработку персональных данных", false),
                                checkbox(FIELD_DIST_PROFILE, "Показывать мой профиль на сайте", true),
                                checkbox(FIELD_DIST_MAP, "Показывать меня на карте", true),
                                checkbox(FIELD_DIST_PURCHASES, "Показывать мои покупки", false)))
                        .build())
                .type(DialogType.multiAction(List.of(
                                submit("Создать аккаунт", onSubmit),
                                link("Оферта", config.offerUrl()),
                                link("Политика", config.privacyUrl())))
                        .columns(3)
                        .build()));
    }

    /** Documents-only window for accounts that predate the current version of them. */
    public Dialog consents(String error, Consumer<DialogResponseView> onSubmit) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Документы VoidRP"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(bodyWithError(List.of(
                                DialogBody.plainMessage(Component.text(
                                        "Мы обновили правила проекта.", NamedTextColor.GRAY), 320),
                                DialogBody.plainMessage(Component.text(
                                        "Чтобы продолжить играть, примите их.", NamedTextColor.GRAY), 320)), error))
                        .inputs(List.of(
                                checkbox(FIELD_OFFER, "Принимаю условия оферты", false),
                                checkbox(FIELD_PERSONAL_DATA, "Согласен на обработку персональных данных", false),
                                checkbox(FIELD_DIST_PROFILE, "Показывать мой профиль на сайте", true),
                                checkbox(FIELD_DIST_MAP, "Показывать меня на карте", true),
                                checkbox(FIELD_DIST_PURCHASES, "Показывать мои покупки", false)))
                        .build())
                .type(DialogType.multiAction(List.of(
                                submit("Принять и играть", onSubmit),
                                link("Оферта", config.offerUrl()),
                                link("Политика", config.privacyUrl())))
                        .columns(3)
                        .build()));
    }

    /** Terminal window: something went wrong and the player has to reconnect. */
    public Dialog notice(String title, String message) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(Component.text(message, NamedTextColor.RED), 320)))
                        .build())
                .type(DialogType.notice()));
    }

    private static List<DialogBody> bodyWithError(List<DialogBody> body, String error) {
        if (error == null || error.isBlank()) {
            return body;
        }
        List<DialogBody> withError = new java.util.ArrayList<>(body);
        withError.add(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED), 320));
        return withError;
    }

    private static DialogInput password(String key, String label) {
        return DialogInput.text(key, Component.text(label))
                .width(300)
                .maxLength(128)
                .build();
    }

    private static DialogInput checkbox(String key, String label, boolean initial) {
        return DialogInput.bool(key, Component.text(label))
                .initial(initial)
                .build();
    }

    private static ActionButton submit(String label, Consumer<DialogResponseView> onSubmit) {
        return ActionButton.builder(Component.text(label))
                .width(150)
                .action(DialogAction.customClick(
                        (response, audience) -> onSubmit.accept(response),
                        ClickCallback.Options.builder().uses(1).build()))
                .build();
    }

    private static ActionButton link(String label, String url) {
        return ActionButton.builder(Component.text(label))
                .width(100)
                .action(DialogAction.staticAction(ClickEvent.openUrl(url)))
                .build();
    }
}
