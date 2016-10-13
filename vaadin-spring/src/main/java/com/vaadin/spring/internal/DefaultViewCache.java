/*
 * Copyright 2015 The original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vaadin.spring.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.ui.UI;

/**
 * Default implementation of {@link com.vaadin.spring.internal.ViewCache}. For
 * internal use only.
 *
 * @author Petter Holmström (petter@vaadin.com)
 */
public class DefaultViewCache implements ViewCache {

	private static final long serialVersionUID = 4634842615905376953L;

	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultViewCache.class);

	private Map<String, ViewBeanStore> beanStores = new HashMap<String, ViewBeanStore>();

	private String viewUnderConstruction = null;

	private String activeView = null;

	/**
	 * Called by {@link com.vaadin.spring.navigator.SpringViewProvider} when a
	 * view scoped view is about to be created.
	 *
	 * @param viewName
	 *            the {@link com.vaadin.spring.annotation.SpringView#name()
	 *            name} of the view (not the bean name).
	 */
	@Override
	public void creatingView(String viewName) {
		LOGGER.trace("Creating view [{}] in cache [{}]", viewName, this);
		getOrCreateBeanStore(viewName);
		this.viewUnderConstruction = viewName;
	}

	/**
	 * Called by {@link com.vaadin.spring.navigator.SpringViewProvider} when a
	 * view scoped view has been created.
	 *
	 * @param viewName
	 *            the {@link com.vaadin.spring.annotation.SpringView#name()
	 *            name} of the view (not the bean name).
	 * @param viewInstance
	 *            the created view instance, or {@code null} if the view could
	 *            not be created.
	 */
	@Override
	public void viewCreated(String viewName, View viewInstance) {
		LOGGER.trace("View [{}] created in cache [{}]", viewName, this);
		this.viewUnderConstruction = null;
		ViewBeanStore beanStore = getOrCreateBeanStore(viewName);
		if (viewInstance == null) {
			LOGGER.trace(	"There was a problem creating the view [{}] in cache [{)], destroying its bean store",
							viewName, this);
			beanStore.destroy();
		}
	}

	private void viewActivated(String viewName) {
		LOGGER.trace("View [{}] activated in cache [{}]", viewName, this);
		this.activeView = viewName;
	}

	private void viewDeactivated(String viewName) {
		LOGGER.trace("View [{}] deactivated in cache [{}], destroying its bean store", viewName, this);
		if (viewName.equals(this.activeView)) {
			this.activeView = null;
		}
		getBeanStore(viewName).destroy();
		LOGGER.trace("Bean stores stored in cache [{}]: {}", this, this.beanStores.size());
	}

	@Override
	public BeanStore getCurrentViewBeanStore() {
		if (this.viewUnderConstruction != null) {
			LOGGER.trace(	"Currently the view [{}] is under construction in cache [{}], returning its bean store",
							this.viewUnderConstruction, this);
			return getBeanStore(this.viewUnderConstruction);
		} else if (this.activeView != null) {
			LOGGER.trace(	"Currently the view [{}] is active in cache [{}], returning its bean store", this.activeView,
							this);
			return getBeanStore(this.activeView);
		} else {
			throw new IllegalStateException("No active view");
		}
	}

	@PreDestroy
	void destroy() {
		LOGGER.trace("View cache [{}] has been destroyed, destroying all bean stores");
		for (ViewBeanStore beanStore : new HashSet<ViewBeanStore>(this.beanStores.values())) {
			beanStore.destroy();
		}
		Assert.isTrue(this.beanStores.isEmpty(), "beanStores should have been emptied by the destruction callbacks");
	}

	private ViewBeanStore getOrCreateBeanStore(final String viewName) {
		ViewBeanStore beanStore = this.beanStores.get(viewName);
		if (beanStore == null) {
			UI ui = getCurrentUI();
			if (ui == null) {
				throw new IllegalStateException("No UI bound to current thread");
			}
			beanStore = new ViewBeanStore(ui, viewName, new BeanStore.DestructionCallback() {

				private static final long serialVersionUID = 5580606280246825742L;

				@Override
				public void beanStoreDestroyed(BeanStore beanStore) {
					DefaultViewCache.this.beanStores.remove(viewName);
				}
			});
			this.beanStores.put(viewName, beanStore);
		}
		return beanStore;
	}

	/**
	 * Returns the current UI.
	 */
	private UI getCurrentUI() {
		return UI.getCurrent();
	}

	private ViewBeanStore getBeanStore(String viewName) {
		ViewBeanStore beanStore = this.beanStores.get(viewName);
		if (beanStore == null) {
			throw new IllegalStateException("The view " + viewName + " has not been created");
		}
		return beanStore;
	}

	class ViewBeanStore extends SessionLockingBeanStore implements ViewChangeListener {

		private static final long serialVersionUID = -7655740852919880134L;

		private final String viewName;
		private final Navigator navigator;

		ViewBeanStore(UI ui, String viewName, DestructionCallback destructionCallback) {
			super(ui.getSession(), ui.getId() + ":" + viewName, destructionCallback);
			this.viewName = viewName;
			this.navigator = ui.getNavigator();
			if (this.navigator == null) {
				throw new IllegalStateException("UI has no Navigator");
			}
			LOGGER.trace("Adding [{}} as view change listener to [{}]", this, this.navigator);
			this.navigator.addViewChangeListener(this);
		}

		@Override
		public void destroy() {
			LOGGER.trace("Removing [{}] as view change listener from [{}]", this, this.navigator);
			this.navigator.removeViewChangeListener(this);
			super.destroy();
		}

		@Override
		public boolean beforeViewChange(ViewChangeEvent viewChangeEvent) {
			return true;
		}

		@Override
		public void afterViewChange(ViewChangeEvent viewChangeEvent) {
			if (this.viewName.equals(viewChangeEvent.getViewName())) {
				viewActivated(this.viewName);
			} else {
				viewDeactivated(this.viewName);
			}
		}
	}
}
