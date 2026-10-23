runStorageAction: function (n, a, s, c) {
  var l = this;
  return Object(j["a"])(Object(O["a"])().mark(function e() {
    var t, o, r, i;
    return Object(O["a"])().wrap(function (e) {
      while (1) switch (e.prev = e.next) {
        case 0:
          if (l.storageService.instance && !l.actionLoading[n]) {
            e.next = 2;
            break;
          }

          return e.abrupt("return");

        case 2:
          return t = l.currentTab, o = l.protocolWideLayout, l.actionLoading[n] = !0, e.prev = 5, e.next = 8, Object(b["g"])(a, l.cleanParams(s));

        case 8:
          if (r = e.sent, i = r[a.toLowerCase() + "response"] || {}, !i.jobid) {
            e.next = 14;
            break;
          }

          l.$pollJob({
            jobId: i.jobid,
            title: c,
            description: l.storageService.instance.name || l.resource.name,
            successMessage: l.$t("label.success"),
            errorMessage: l.$t("label.error"),
            loadingMessage: l.$t("label.loading") + "...",
            catchMessage: l.$t("error.fetching.async.job.result"),
            action: {
              isFetchData: !1
            },
            originalPage: l.$route.path,
            successMethod: function () {
              return l.refreshAfterStorageAction(n, t, o);
            }
          }), e.next = 16;
          break;

        case 14:
          return e.next = 16, l.refreshAfterStorageAction(n, t, o);

        case 16:
          e.next = 21;
          break;

        case 18:
          e.prev = 18, e.t0 = e["catch"](5), l.$notifyError(e.t0);

        case 21:
          return e.prev = 21, l.actionLoading[n] = !1, e.finish(21);

        case 24:
        case "end":
          return e.stop();
      }
    }, e, null, [[5, 18, 21, 24]]);
  }))();
}

closeActionModal: function () {
  this.actionModal.visible = !1, this.actionModal.type = "", this.actionModal.context = null, this.actionModal.loading = !1;
}

submitActionModal: function () {
  var r = this;
  return Object(j["a"])(Object(O["a"])().mark(function e() {
    var t, o;
    return Object(O["a"])().wrap(function (e) {
      while (1) switch (e.prev = e.next) {
        case 0:
          if (t = {
            enableProtocol: r.enableProtocol,
            nfsExport: r.createNfsExport,
            editNfsExport: r.updateNfsExport,
            nfsAcl: r.createNfsAcl,
            editNfsAcl: r.updateNfsAcl,
            deleteConfirm: r.deleteStorageResource,
            deleteEndpoint: r.deleteStorageEndpoint,
            smbShare: r.createSmbShare,
            editSmbShare: r.updateSmbShare,
            smbAcl: r.createSmbAcl,
            editSmbAcl: r.updateSmbAcl,
            adJoin: r.joinAdDomain,
            adRejoin: r.joinAdDomain,
            adLeave: r.leaveAdDomain,
            iscsiTarget: r.createIscsiTarget,
            editIscsiTarget: r.updateIscsiTarget,
            iscsiAcl: r.createIscsiAcl,
            editIscsiAcl: r.updateIscsiAcl,
            nvmePrepare: r.prepareNvmeOf,
            nvmeSubsystem: r.createNvmeSubsystem,
            editNvmeSubsystem: r.updateNvmeSubsystem,
            nvmeNamespace: r.createNvmeNamespace,
            editNvmeNamespace: r.updateNvmeNamespace,
            nvmeHostAcl: r.createNvmeHostAcl,
            editNvmeHostAcl: r.updateNvmeHostAcl,
            attachVolume: r.attachExistingVolume,
            resizeShare: r.resizeFileShare,
            resizeBackingVolume: r.resizeBackingVolume,
            detachBackingVolume: r.detachBackingVolume,
            disconnectSession: r.disconnectSession
          }, o = t[r.actionModal.type], o) {
            e.next = 5;
            break;
          }

          return r.closeActionModal(), e.abrupt("return");

        case 5:
          return r.actionModal.loading = !0, e.next = 8, o.call(r);

        case 8:
          r.closeActionModal();

        case 9:
        case "end":
          return e.stop();
      }
    }, e);
  }))();
}
