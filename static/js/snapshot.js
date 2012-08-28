/*************************************************************************
 * Copyright 2009-2012 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/

(function($, eucalyptus) {
  $.widget('eucalyptus.snapshot', $.eucalyptus.eucawidget, {
    options : { },
    baseTable : null,
    tableWrapper : null,
    delDialog : null,
    createDialog : null,
    _init : function() {
      var thisObj = this;
      var $tmpl = $('html body').find('.templates #snapshotTblTmpl').clone();
      var $wrapper = $($tmpl.render($.extend($.i18n.map, help_volume)));
      var $snapshotTable = $wrapper.children().first();
      var $snapshotHelp = $wrapper.children().last();
      this.baseTable = $snapshotTable;
      this.element.add($snapshotTable);
      this.tableWrapper = $snapshotTable.eucatable({
        id : 'snapshots', // user of this widget should customize these options,
        dt_arg : {
          "bProcessing": true,
          "sAjaxSource": "../ec2?Action=DescribeSnapshots",
          "sAjaxDataProp": "results",
          "bAutoWidth" : false,
          "sPaginationType": "full_numbers",
          "aoColumns": [
            {
              "bSortable": false,
              "fnRender": function(oObj) { return '<input type="checkbox"/>' },
              "sWidth": "20px",
            },
            { "mDataProp": "id" },
            {
              "fnRender": function(oObj) { 
                 $div = $('<div>').addClass('table-legend-item').attr('id', 'legend-snapshots-'+oObj.aData.status);
                 $div.append(oObj.aData.status=='pending' ?  oObj.aData.progress : '&nbsp;');
                 return asHTML($div);
               },
              "sWidth": "20px",
              "bSearchable": false,
              "iDataSort": 7, // sort on hiden status column
            },
            { "mDataProp": "volume_size" },
            { "mDataProp": "volume_id" },
            { "mDataProp": "description" },
            // output start time in browser format and timezone
            { "fnRender": function(oObj) { d = new Date(oObj.aData.start_time); return d.toLocaleString(); } },
            {
              "bVisible": false,
              "mDataProp": "status"
            }
          ],
        },
        text : {
          header_title : snapshot_h_title,
          create_resource : snapshot_create,
          resource_found : snapshot_found,
        },
        menu_actions : function(args){ 
          return thisObj._createMenuActions();
        },
        context_menu_actions : function(row) {
          return thisObj._createMenuActions();
        },
        menu_click_create : function (args) { thisObj._createAction() },
        help_click : function(evt) {
          thisObj._flipToHelp(evt, $snapshotHelp);
        },
        filters : [{name:"snap_state", options: ['all','in-progress','completed'], filter_col:7, alias: {'in-progress':'pending','completed':'completed'}}],
        legend : ['pending', 'completed', 'error'],
      });
      this.tableWrapper.appendTo(this.element);

      // snapshot delete dialog start
      $tmpl = $('html body').find('.templates #snapshotDelDlgTmpl').clone();
      var $rendered = $($tmpl.render($.extend($.i18n.map, help_volume)));
      var $del_dialog = $rendered.children().first();
      var $del_help = $rendered.children().last();
      this.delDialog = $del_dialog.eucadialog({
         id: 'snapshots-delete',
         title: snapshot_delete_dialog_title,
         buttons: {
           'delete': {text: snapshot_dialog_del_btn, click: function() { thisObj._deleteListedSnapshots(); $del_dialog.dialog("close");}},
           'cancel': {text: snapshot_dialog_cancel_btn, focus:true, click: function() { $del_dialog.dialog("close");}} 
         },
         help: {title: help_volume['dialog_delete_title'], content: $del_help},
       });
      // snapshot delete dialog end
      // create snapshot dialog end
      $tmpl = $('html body').find('.templates #snapshotCreateDlgTmpl').clone();
      var $rendered = $($tmpl.render($.extend($.i18n.map, help_volume)));
      var $snapshot_dialog = $rendered.children().first();
      var $snapshot_dialog_help = $rendered.children().last();
      this.createDialog = $snapshot_dialog.eucadialog({
         id: 'snapshot-create-from-snapshot',
         title: snapshot_create_dialog_title,
         buttons: {
           'create': { text: snapshot_create_dialog_create_btn, click: function() { 
                volumeId = $snapshot_dialog.find('#snapshot-create-volume-selector').val();
                description = $.trim($snapshot_dialog.find('#snapshot-create-description').val());
                thisObj._createSnapshot(volumeId, description);
                $snapshot_dialog.dialog("close");
              } 
            },
           'cancel': { text: snapshot_dialog_cancel_btn, focus:true, click: function() { $snapshot_dialog.dialog("close"); } }
         },
         help: {title: help_volume['dialog_snapshot_create_title'], content: $snapshot_dialog_help},
         on_open: {spin: true, callback: function(args) {
           var dfd = $.Deferred();
           thisObj._initCreateDialog(dfd) ; // pulls volumes info from the server
           return dfd.promise();
         }},
       });
      // create snapshot dialog end
    },

    _create : function() { 
    },

    _destroy : function() {
    },

    _createMenuActions : function() {
      thisObj = this;
      selectedsnapshots = thisObj.baseTable.eucatable('getSelectedRows', 7); // 7th column=status (this is snapshot's knowledge)
      var itemsList = {};
      if ( selectedsnapshots.length > 0 ){
        itemsList['delete'] = { "name": snapshot_action_delete, callback: function(key, opt) { thisObj._deleteAction(); } }
      }
      return itemsList;
    },

    _initCreateDialog : function(dfd) { // method should resolve dfd object
      $.ajax({
        type:"GET",
        url:"/ec2?Action=DescribeVolumes",
        data:"_xsrf="+$.cookie('_xsrf'),
        dataType:"json",
        async:false,
        cache:false,
        success:
          function(data, textStatus, jqXHR){
            $volSelector = $('#snapshot-create-volume-selector').html('');
     //       $volSelector.append($('<option>').attr('value', '').text($.i18n.map['selection_none']));
            if ( data.results ) {
              for( res in data.results) {
                volume = data.results[res];
                if ( volume.status === 'in-use' || volume.status === 'available' ) {
                  $volSelector.append($('<option>').attr('value', volume.id).text(volume.id));
                }
              } 
              dfd.resolve();
            } else {
              notifyError(null, error_loading_volumes_msg);
              dfd.reject();
            }
          },
        error:
          function(jqXHR, textStatus, errorThrown){
            notifyError(null, error_loading_volumes_msg);
            dfd.reject();
          }
      });
    },

    _getSnapshotId : function(rowSelector) {
      return $(rowSelector).find('td:eq(1)').text();
    },

    _deleteListedSnapshots : function () {
      var thisObj = this;
      $snapshotsToDelete = this.delDialog.find("#snapshots-to-delete");
      var rowsToDelete = $snapshotsToDelete.text().split(ID_SEPARATOR);
      for ( i = 0; i<rowsToDelete.length; i++ ) {
        var snapshotId = rowsToDelete[i];
        $.ajax({
          type:"GET",
          url:"/ec2?Action=DeleteSnapshot&SnapshotId=" + snapshotId,
          data:"_xsrf="+$.cookie('_xsrf'),
          dataType:"json",
          async:true,
          success:
          (function(snapshotId) {
            return function(data, textStatus, jqXHR){
              if ( data.results && data.results == true ) {
                notifySuccess(null, snapshot_delete_success + ' ' + snapshotId);
                thisObj.tableWrapper.eucatable('refreshTable');
              } else {
                notifyError(null, snapshot_delete_error + ' ' + snapshotId);
              }
           }
          })(snapshotId),
          error:
          (function(snapshotId) {
            return function(jqXHR, textStatus, errorThrown){
              notifyError(null, snapshot_delete_error + ' ' + snapshotId);
            }
          })(snapshotId)
        });
      }
    },

    _createSnapshot : function (volumeId, description) {
      var thisObj = this;
      $.ajax({
        type:"GET",
        url:"/ec2?Action=CreateSnapshot&VolumeId=" + volumeId + "&Description=" + description,
        data:"_xsrf="+$.cookie('_xsrf'),
        dataType:"json",
        async:true,
        success:
          function(data, textStatus, jqXHR){
            if ( data.results ) {
              notifySuccess(null, snapshot_create_success + ' ' + volumeId);
              thisObj.tableWrapper.eucatable('refreshTable');
            } else {
              notifyError(null, snapshot_create_error + ' ' + volumeId);
            }
          },
        error:
          function(jqXHR, textStatus, errorThrown){
            notifyError(null, snapshot_create_error + ' ' + volumeId);
          }
      });
    },

    _deleteAction : function(snapshotId) {
      var thisObj = this;
      snapshotsToDelete = [];
      if ( !snapshotId ) {
        snapshotsToDelete = thisObj.tableWrapper.eucatable('getSelectedRows', 1);
      } else {
        snapshotsToDelete[0] = snapshotId;
      }

      if ( snapshotsToDelete.length > 0 ) {
        thisObj.delDialog.eucadialog('setSelectedResources', snapshotsToDelete);
        $snapshotsToDelete = thisObj.delDialog.find("#snapshots-to-delete");
        $snapshotsToDelete.html(snapshotsToDelete.join(ID_SEPARATOR));
        thisObj.delDialog.dialog('open');
      }
    },

    _createAction : function() {
      this.createDialog.eucadialog('open');
    },

/**** Public Methods ****/
    close: function() {
      this._super('close');
    },
/**** End of Public Methods ****/
  });
})
(jQuery, window.eucalyptus ? window.eucalyptus : window.eucalyptus = {});
