# top-level Eucalyptus makefile
#
# $Id: Makefile,v 1.5 2008-12-24 18:28:07 nurmi Exp $
#

include Makedefs

# notes: storage has to preceed node and node has to preceed cluster
SUBDIRS			=	tools \
				util \
				net \
				storage	 \
				gatherlog \
				node  \
				cluster \
				clc

# files we are going to package
DIST_FILES		=	CHANGELOG \
				configure \
				eucalyptus.spec.in \
				INSTALL \
				install-sh \
				LICENSE \
				Makedefs.in \
				Makefile \
				README \
				VERSION
DIST_NAME		= $(DIST_DIR).tgz

.PHONY: all clean distclean build dist

all: build

help:
	@echo; echo "Available targets:"
	@echo "   all          this is the default target: it builds eucalyptus"
	@echo "   install      install eucalyptus"
	@echo "   clean        remove objects file and compile by-products"
	@echo "   distclean    restore the source tree to a pristine state"
	@echo 


tags:
	@echo making tags for emacs and vi
	find cluster net node storage tools util -name "*.[chCH]" -print | ctags -L -
	find cluster net node storage tools util -name "*.[chCH]" -print | etags -L -

build: Makedefs 
	@for subdir in $(SUBDIRS); do \
		(cd $$subdir && $(MAKE) $@) || exit $$? ; done

deploy: build
	@for subdir in $(SUBDIRS); do \
		(cd $$subdir && $(MAKE) $@) || exit $$? ; done

install: deploy
	@$(INSTALL) -d $(prefix)
	@$(INSTALL) -d $(etcdir)/eucalyptus/cloud.d
	@$(INSTALL) -m 0644 VERSION $(etcdir)/eucalyptus/eucalyptus-version
	@$(INSTALL) -d $(etcdir)/init.d
	@$(INSTALL) -d $(vardir)/run/eucalyptus/net
	@$(INSTALL) -m 0700 -d $(vardir)/lib/eucalyptus/keys
	@$(INSTALL) -d $(vardir)/log/eucalyptus
	@$(INSTALL) -d $(datarootdir)/eucalyptus
	@$(INSTALL) -d $(usrdir)/sbin
	@for subdir in $(SUBDIRS); do \
		(cd $$subdir && $(MAKE) $@) || exit $$? ; done

dist:
	@rm -rf $(DIST_ROOT) $(DIST_NAME)
	@$(INSTALL) -d $(DIST_ROOT)
	@$(INSTALL) $(DIST_FILES) $(DIST_ROOT)
	@for subdir in $(SUBDIRS); do \
		(cd $$subdir && $(MAKE) $@) || exit $$? ; done

clean:
	@for subdir in $(SUBDIRS); do \
		(cd $$subdir && $(MAKE) $@) || exit $$? ; done

distclean: clean
	@for subdir in $(SUBDIRS); do \
		(cd $$subdir && $(MAKE) $@) || exit $$? ; done
	@rm -f config.cache config.log config.status Makedefs tags TAGS eucalyptus*spec
	@# they where part of CLEAN
	@rm -rf lib 

# the following target is used to remove eucalyptuys from your system
uninstall:
	@echo something to do here


Makedefs: Makedefs.in config.status
	./config.status

config.status: configure
	@if test ! -x ./config.status; then \
		echo "you have to run ./configure!"; exit 1; fi
	./config.status --recheck

# DO NOT DELETE
