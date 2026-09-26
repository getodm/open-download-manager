#!/bin/sh
# Extract both Java and GTK text into one template; merge without guessing translations.
set -eu
cd "$(dirname "$0")/.."
xgettext --language=Java --from-code=UTF-8 --keyword=I18n.tr:1 --keyword=I18n.mark:1 \
    --keyword=I18n.format:1 --keyword=I18n.plural:1,2 \
    --keyword=I18n.context:1c,2 \
    --flag=I18n.format:1:java-printf-format --flag=I18n.plural:1:java-printf-format \
    --flag=I18n.plural:2:java-printf-format --sort-output \
    --package-name=odm --package-version=0.3.1 --msgid-bugs-address=https://github.com/albilu/open-download-manager/issues \
    --output=po/odm.pot src/main/java/org/odm/gtk4/*.java
xgettext --language=Glade --from-code=UTF-8 --join-existing --sort-output \
    --package-name=odm --package-version=0.3.1 --msgid-bugs-address=https://github.com/albilu/open-download-manager/issues \
    --output=po/odm.pot src/main/resources/ui/*.ui
for catalog in po/*.po; do
    msgmerge --update --backup=none --no-fuzzy-matching "$catalog" po/odm.pot
done
