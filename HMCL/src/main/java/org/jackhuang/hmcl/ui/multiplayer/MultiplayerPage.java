/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.multiplayer;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import de.javawi.jstun.test.DiscoveryInfo;
import de.javawi.jstun.test.FastDiscoveryTest;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import org.jackhuang.hmcl.event.Event;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.StringUtils;

import java.io.FileNotFoundException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.jackhuang.hmcl.setting.ConfigHolder.globalConfig;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.Lang.resolveException;
import static org.jackhuang.hmcl.util.Logging.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class MultiplayerPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("multiplayer")));

    private final ObjectProperty<MultiplayerManager.State> multiplayerState = new SimpleObjectProperty<>(MultiplayerManager.State.DISCONNECTED);
    private final ReadOnlyStringWrapper token = new ReadOnlyStringWrapper();
    private final ReadOnlyObjectWrapper<DiscoveryInfo> natState = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyIntegerWrapper gamePort = new ReadOnlyIntegerWrapper(-1);
    private final ReadOnlyObjectWrapper<MultiplayerManager.CatoSession> session = new ReadOnlyObjectWrapper<>();
    private final ObservableList<MultiplayerChannel.CatoClient> clients = FXCollections.observableArrayList();

    private Consumer<MultiplayerManager.CatoExitEvent> onExit;
    private Consumer<MultiplayerManager.CatoIdEvent> onIdGenerated;
    private Consumer<Event> onPeerConnected;

    public MultiplayerPage() {
        testNAT();
    }

    @Override
    public void onPageShown() {
        checkAgreement(this::downloadCatoIfNecessary);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new MultiplayerPageSkin(this);
    }

    public ObservableList<MultiplayerChannel.CatoClient> getClients() {
        return clients;
    }

    public MultiplayerManager.State getMultiplayerState() {
        return multiplayerState.get();
    }

    public ObjectProperty<MultiplayerManager.State> multiplayerStateProperty() {
        return multiplayerState;
    }

    public void setMultiplayerState(MultiplayerManager.State multiplayerState) {
        this.multiplayerState.set(multiplayerState);
    }

    public DiscoveryInfo getNatState() {
        return natState.get();
    }

    public ReadOnlyObjectProperty<DiscoveryInfo> natStateProperty() {
        return natState.getReadOnlyProperty();
    }

    public String getToken() {
        return token.get();
    }

    public ReadOnlyStringProperty tokenProperty() {
        return token.getReadOnlyProperty();
    }

    public int getGamePort() {
        return gamePort.get();
    }

    public ReadOnlyIntegerProperty gamePortProperty() {
        return gamePort.getReadOnlyProperty();
    }

    public MultiplayerManager.CatoSession getSession() {
        return session.get();
    }

    public ReadOnlyObjectProperty<MultiplayerManager.CatoSession> sessionProperty() {
        return session.getReadOnlyProperty();
    }

    private void testNAT() {
        Task.supplyAsync(() -> {
            FastDiscoveryTest tester = new FastDiscoveryTest(null, 0, "stun.stunprotocol.org", 3478);
            return tester.test();
        }).whenComplete(Schedulers.javafx(), (info, exception) -> {
            LOG.log(Level.INFO, "Nat test result " + MultiplayerPageSkin.getNATType(info), exception);
            if (exception == null) {
                natState.set(info);
            } else {
                natState.set(null);
            }
        }).start();
    }

    private void checkAgreement(Runnable runnable) {
        if (globalConfig().getMultiplayerAgreementVersion() < MultiplayerManager.CATO_AGREEMENT_VERSION) {
            JFXDialogLayout agreementPane = new JFXDialogLayout();
            agreementPane.setHeading(new Label(i18n("launcher.agreement")));
            agreementPane.setBody(new Label(i18n("multiplayer.agreement.prompt")));
            JFXHyperlink agreementLink = new JFXHyperlink(i18n("launcher.agreement"));
            agreementLink.setOnAction(e -> FXUtils.openLink("https://noin.cn/agreement"));
            JFXButton yesButton = new JFXButton(i18n("launcher.agreement.accept"));
            yesButton.getStyleClass().add("dialog-accept");
            yesButton.setOnAction(e -> {
                globalConfig().setMultiplayerAgreementVersion(MultiplayerManager.CATO_AGREEMENT_VERSION);
                runnable.run();
                agreementPane.fireEvent(new DialogCloseEvent());
            });
            JFXButton noButton = new JFXButton(i18n("launcher.agreement.decline"));
            noButton.getStyleClass().add("dialog-cancel");
            noButton.setOnAction(e -> {
                agreementPane.fireEvent(new DialogCloseEvent());
                fireEvent(new PageCloseEvent());
            });
            agreementPane.setActions(agreementLink, yesButton, noButton);
            Controllers.dialog(agreementPane);
        } else {
            runnable.run();
        }
    }

    private void downloadCatoIfNecessary() {
        if (StringUtils.isBlank(MultiplayerManager.getCatoPath())) {
            Controllers.dialog(i18n("multiplayer.download.unsupported"), i18n("install.failed.downloading"), MessageDialogPane.MessageType.ERROR);
            fireEvent(new PageCloseEvent());
            return;
        }

        if (!MultiplayerManager.getCatoExecutable().toFile().exists()) {
            setDisabled(true);
            TaskExecutor executor = MultiplayerManager.downloadCato()
                    .whenComplete(Schedulers.javafx(), exception -> {
                        setDisabled(false);
                        if (exception != null) {
                            if (exception instanceof CancellationException) {
                                Controllers.showToast(i18n("message.cancelled"));
                            } else {
                                Controllers.dialog(DownloadProviders.localizeErrorMessage(exception), i18n("install.failed.downloading"), MessageDialogPane.MessageType.ERROR);
                                fireEvent(new PageCloseEvent());
                            }
                        } else {
                            Controllers.showToast(i18n("multiplayer.download.success"));
                        }
                    }).executor();
            Controllers.taskDialog(executor, i18n("multiplayer.download"));
            executor.start();
        } else {
            setDisabled(false);
        }
    }

    public void copyInvitationCode() {
        if (getSession() == null || !getSession().isReady() || gamePort.get() < 0 || getMultiplayerState() != MultiplayerManager.State.MASTER) {
            throw new IllegalStateException("CatoSession not ready");
        }

        FXUtils.copyText(getSession().generateInvitationCode(getSession().getServer().getPort()));
    }

    public void createRoom() {
        if (getSession() != null || getMultiplayerState() != MultiplayerManager.State.DISCONNECTED) {
            throw new IllegalStateException("CatoSession already ready");
        }

        Controllers.dialog(new CreateMultiplayerRoomDialog((result, resolve, reject) -> {
            int gamePort = result.getServer().getAd();
            try {
                MultiplayerManager.CatoSession session = MultiplayerManager.createSession(globalConfig().getMultiplayerToken(), result.getServer().getMotd(), gamePort, result.isAllowAllJoinRequests());
                session.getServer().setOnClientAdding((client, resolveClient, rejectClient) -> {
                    runInFX(() -> {
                        Controllers.dialog(new MessageDialogPane.Builder(i18n("multiplayer.session.create.join.prompt", client.getUsername()), i18n("multiplayer.session.create.join"), MessageDialogPane.MessageType.INFO)
                                .yesOrNo(resolveClient, () -> rejectClient.accept(i18n("multiplayer.session.join.wait_timeout")))
                                .cancelOnTimeout(30 * 1000)
                                .build());
                    });
                });
                session.getServer().onClientAdded().register(event -> {
                    runInFX(() -> {
                        clients.add(event);
                    });
                });
                session.getServer().onClientDisconnected().register(event -> {
                    runInFX(() -> {
                        clients.remove(event);
                    });
                });
                initCatoSession(session);
            } catch (MultiplayerManager.CatoAlreadyStartedException e) {
                LOG.log(Level.WARNING, "Cato already started", e);
                reject.accept(i18n("multiplayer.session.error.already_started"));
                return;
            } catch (FileNotFoundException e) {
                LOG.log(Level.WARNING, "Cato not found", e);
                reject.accept(i18n("multiplayer.session.error.file_not_found"));
                return;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to create session", e);
                reject.accept(i18n("multiplayer.session.create.error") + e.getLocalizedMessage());
                return;
            }

            this.gamePort.set(gamePort);
            setMultiplayerState(MultiplayerManager.State.CONNECTING);
            resolve.run();
        }));
    }

    public void joinRoom() {
        if (getSession() != null || getMultiplayerState() != MultiplayerManager.State.DISCONNECTED) {
            throw new IllegalStateException("CatoSession already ready");
        }

        Controllers.prompt(new PromptDialogPane.Builder(i18n("multiplayer.session.join"), (result, resolve, reject) -> {
            PromptDialogPane.Builder.HintQuestion hintQuestion = (PromptDialogPane.Builder.HintQuestion) result.get(0);

            String invitationCode = ((PromptDialogPane.Builder.StringQuestion) result.get(1)).getValue();
            MultiplayerManager.Invitation invitation;
            try {
                invitation = MultiplayerManager.parseInvitationCode(invitationCode);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to join session", e);
                reject.accept(i18n("multiplayer.session.join.invitation_code.error"));
                return;
            }

            int localPort; // invitation channel
            try {
                localPort = MultiplayerManager.findAvailablePort();
            } catch (Exception e) {
                reject.accept(i18n("multiplayer.session.join.port.error"));
                return;
            }

            try {
                MultiplayerManager.joinSession(
                                globalConfig().getMultiplayerToken(),
                                invitation.getVersion(),
                                invitation.getSessionName(),
                                invitation.getId(),
                                globalConfig().isMultiplayerRelay() && StringUtils.isNotBlank(globalConfig().getMultiplayerToken())
                                        ? MultiplayerManager.Mode.RELAY
                                        : MultiplayerManager.Mode.P2P,
                                invitation.getChannelPort(),
                                localPort, new MultiplayerManager.JoinSessionHandler() {
                                    @Override
                                    public void onWaitingForJoinResponse() {
                                        runInFX(() -> {
                                            hintQuestion.setQuestion(i18n("multiplayer.session.join.wait"));
                                        });
                                    }
                                })
                        .thenAcceptAsync(session -> {
                            initCatoSession(session);

                            AtomicBoolean kicked = new AtomicBoolean();

                            session.getClient().onDisconnected().register(() -> {
                                runInFX(() -> {
                                    stopCatoSession();
                                    if (!kicked.get()) {
                                        Controllers.dialog(i18n("multiplayer.session.join.lost_connection"));
                                    }
                                });
                            });

                            session.getClient().onKicked().register(() -> {
                                runInFX(() -> {
                                    kicked.set(true);
                                    Controllers.dialog(i18n("multiplayer.session.join.kicked"));
                                });
                            });

                            gamePort.set(session.getClient().getGamePort());
                            setMultiplayerState(MultiplayerManager.State.SLAVE);
                            resolve.run();
                        }, Platform::runLater)
                        .exceptionally(throwable -> {
                            Throwable resolved = resolveException(throwable);
                            if (resolved instanceof CancellationException) {
                                LOG.info("Connection rejected by the server");
                                reject.accept(i18n("multiplayer.session.join.rejected"));
                                return null;
                            } else if (resolved instanceof MultiplayerManager.CatoAlreadyStartedException) {
                                LOG.info("Cato already started");
                                reject.accept(i18n("multiplayer.session.error.already_started"));
                                return null;
                            } else if (resolved instanceof FileNotFoundException) {
                                LOG.log(Level.WARNING, "Cato not found", resolved);
                                reject.accept(i18n("multiplayer.session.error.file_not_found"));
                                return null;
                            } else if (resolved instanceof MultiplayerManager.JoinRequestTimeoutException) {
                                LOG.info("Cato already started");
                                reject.accept(i18n("multiplayer.session.join.wait_timeout"));
                                return null;
                            } else if (resolved instanceof MultiplayerManager.ConnectionErrorException) {
                                LOG.info("Failed to establish connection with server");
                                reject.accept(i18n("multiplayer.session.join.error.connection"));
                                return null;
                            } else {
                                LOG.log(Level.WARNING, "Failed to join session", resolved);
                                reject.accept(i18n("multiplayer.session.join.error"));
                            }
                            return null;
                        });
            } catch (MultiplayerManager.IncompatibleCatoVersionException e) {
                reject.accept(i18n("multiplayer.session.join.invitation_code.version"));
            }
        })
                .addQuestion(new PromptDialogPane.Builder.HintQuestion(i18n("multiplayer.session.join.hint")))
                .addQuestion(new PromptDialogPane.Builder.StringQuestion(i18n("multiplayer.session.join.invitation_code"), "", new RequiredValidator())));
    }

    public void kickPlayer(MultiplayerChannel.CatoClient client) {
        if (getSession() == null || !getSession().isReady() || getMultiplayerState() != MultiplayerManager.State.MASTER) {
            throw new IllegalStateException("CatoSession not ready");
        }

        Controllers.confirm(i18n("multiplayer.session.create.members.kick.prompt"), i18n("multiplayer.session.create.members.kick"), MessageDialogPane.MessageType.WARNING,
                () -> {
                    getSession().getServer().kickPlayer(client);
                }, null);
    }

    public void closeRoom() {
        if (getSession() == null || !getSession().isReady() || getMultiplayerState() != MultiplayerManager.State.MASTER) {
            throw new IllegalStateException("CatoSession not ready");
        }

        Controllers.confirm(i18n("multiplayer.session.close.warning"), i18n("message.warning"), MessageDialogPane.MessageType.WARNING,
                this::stopCatoSession, null);
    }

    public void quitRoom() {
        if (getSession() == null || !getSession().isReady() || getMultiplayerState() != MultiplayerManager.State.SLAVE) {
            throw new IllegalStateException("CatoSession not ready");
        }

        Controllers.confirm(i18n("multiplayer.session.quit.warning"), i18n("message.warning"), MessageDialogPane.MessageType.WARNING,
                this::stopCatoSession, null);
    }

    public void cancelRoom() {
        if (getSession() == null || getSession().isReady() || getMultiplayerState() != MultiplayerManager.State.CONNECTING) {
            throw new IllegalStateException("CatoSession not existing or already ready");
        }

        stopCatoSession();
    }

    private void initCatoSession(MultiplayerManager.CatoSession session) {
        runInFX(() -> {
            onExit = session.onExit().registerWeak(this::onCatoExit);
            onIdGenerated = session.onIdGenerated().registerWeak(this::onCatoIdGenerated);
            onPeerConnected = session.onPeerConnected().registerWeak(this::onCatoPeerConnected);

            this.clients.clear();
            this.session.set(session);
        });
    }

    private void stopCatoSession() {
        if (getSession() != null) {
            getSession().stop();
        }
        clearCatoSession();
    }

    private void clearCatoSession() {
        this.session.set(null);
        this.token.set(null);
        this.gamePort.set(-1);
        this.multiplayerState.set(MultiplayerManager.State.DISCONNECTED);
    }

    private void onCatoExit(MultiplayerManager.CatoExitEvent event) {
        runInFX(() -> {
            boolean ready = ((MultiplayerManager.CatoSession) event.getSource()).isReady();
            switch (event.getExitCode()) {
                case 0:
                    break;
                case MultiplayerManager.CatoExitEvent.EXIT_CODE_SESSION_EXPIRED:
                    Controllers.dialog(i18n("multiplayer.session.expired"));
                    break;
                case 1:
                    if (!ready) {
                        Controllers.dialog(i18n("multiplayer.exit.timeout"));
                    }
                    break;
                case -1:
                    // do nothing
                    break;
                default:
                    if (!((MultiplayerManager.CatoSession) event.getSource()).isReady()) {
                        Controllers.dialog(i18n("multiplayer.exit.before_ready", event.getExitCode()));
                    } else {
                        Controllers.dialog(i18n("multiplayer.exit.after_ready", event.getExitCode()));
                    }
                    break;
            }

            clearCatoSession();
        });
    }

    private void onCatoPeerConnected(Event event) {
        runInFX(() -> {
        });
    }

    private void onCatoIdGenerated(MultiplayerManager.CatoIdEvent event) {
        runInFX(() -> {
            token.set(event.getId());
            setMultiplayerState(((MultiplayerManager.CatoSession) event.getSource()).getType());
        });
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state;
    }
}
