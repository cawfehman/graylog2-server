import Reflux from 'reflux';

import { qualifyUrl } from 'util/URLUtils';
import fetch from 'logic/rest/FetchProvider';
import ApiRoutes from 'routing/ApiRoutes';

import CombinedProvider from 'injection/CombinedProvider';

const { SessionStore, SessionActions } = CombinedProvider.get('Session');
const { StartpageStore } = CombinedProvider.get('Startpage');
const { PreferencesActions } = CombinedProvider.get('Preferences');

const CurrentUserStore = Reflux.createStore({
  listenables: [SessionActions],
  currentUser: undefined,

  init() {
    this.listenTo(SessionStore, this.sessionUpdate, this.sessionUpdate);
    this.listenTo(StartpageStore, this.reload, this.reload);
    PreferencesActions.saveUserPreferences.completed.listen(this.reload);
  },

  getInitialState() {
    return { currentUser: this.currentUser };
  },

  get() {
    return this.currentUser;
  },

  sessionUpdate(sessionInfo) {
    if (sessionInfo.sessionId && sessionInfo.username) {
      const { username } = sessionInfo;
      this.update(username);
    } else {
      this.currentUser = undefined;
      this.trigger({ currentUser: this.currentUser });
    }
  },

  reload() {
    if (this.currentUser !== undefined) {
      return this.update(this.currentUser.username);
    }
    return Promise.resolve();
  },

  update(username) {
    return fetch('GET', qualifyUrl(ApiRoutes.UsersApiController.load(encodeURIComponent(username)).url))
      .then((resp) => {
        this.currentUser = resp;
        this.trigger({ currentUser: this.currentUser });
      });
  },
});

export default CurrentUserStore;
