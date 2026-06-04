##########################################################################################
#
# Magisk Module Installer Script
# Copperhead GSM-SIP Gateway
#
##########################################################################################

SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false

REPLACE=""

print_modname() {
  ui_print "***********************************"
  ui_print "*  Copperhead GSM-SIP Gateway     *"
  ui_print "*  Privileged Permissions Module   *"
  ui_print "***********************************"
}

on_install() {
  ui_print "- Extracting module files"
  unzip -o "$ZIPFILE" 'system/*' -d $MODPATH >&2
  set_permissions
}

set_permissions() {
  set_perm_recursive $MODPATH 0 0 0755 0644
}
