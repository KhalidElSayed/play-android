/*
 * Copyright 2012 Kevin Sawicki <kevinsawicki@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.play.app;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static com.github.play.app.PlayActivity.ACTION_QUEUE;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.github.kevinsawicki.wishlist.Toaster;
import com.github.kevinsawicki.wishlist.ViewUtils;
import com.github.play.R.id;
import com.github.play.R.layout;
import com.github.play.R.string;
import com.github.play.core.PlayPreferences;
import com.github.play.core.PlayService;
import com.github.play.core.QueueSongsTask;
import com.github.play.core.Song;
import com.github.play.core.SongResult;
import com.github.play.widget.SearchListAdapter;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base activity to display a list of songs and optionally queue them
 */
public abstract class SongViewActivity extends SherlockActivity implements
		OnItemClickListener {

	/**
	 * Play service reference
	 */
	protected final AtomicReference<PlayService> service = new AtomicReference<PlayService>();

	private MenuItem addItem;

	/**
	 * List view
	 */
	protected ListView listView;

	private View loadingView;

	private SearchListAdapter adapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(layout.search);

		loadingView = findViewById(id.ll_loading);

		listView = (ListView) findViewById(android.R.id.list);
		listView.setOnItemClickListener(this);
		adapter = new SearchListAdapter(this, service);
		listView.setAdapter(adapter);

		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		PlayPreferences settings = new PlayPreferences(this);
		service.set(new PlayService(settings.getUrl(), settings.getToken()));

		refreshSongs();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu optionsMenu) {
		addItem = optionsMenu.findItem(id.m_add);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		updateAddItem();
		return true;
	}

	/**
	 * Show/hide loading view
	 *
	 * @param loading
	 */
	protected void showLoading(final boolean loading) {
		ViewUtils.setGone(loadingView, !loading);
		ViewUtils.setGone(listView, loading);
	}

	/**
	 * Refresh songs being displayed
	 */
	protected abstract void refreshSongs();

	/**
	 * Display loaded songs
	 *
	 * @param result
	 */
	protected void displaySongs(final SongResult result) {
		if (result.exception == null)
			adapter.setSongs(result);
		else
			Toaster.showLong(SongViewActivity.this, string.search_failed);

		showLoading(false);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			Intent intent = new Intent(this, PlayActivity.class);
			intent.addFlags(FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP);
			startActivity(intent);
			return true;
		case id.m_refresh:
			refreshSongs();
			return true;
		case id.m_add:
			queueSelectedSongs();
			return true;
		case id.m_select_all:
			selectAllSongs();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	/**
	 * Select all songs
	 */
	protected void selectAllSongs() {
		for (int i = 0; i < adapter.getCount(); i++)
			adapter.setSelected(i, true);

		adapter.notifyDataSetChanged();
		updateTitle();
		updateAddItem();
	}

	/**
	 * Show/hide add menu item
	 *
	 * @param show
	 */
	protected void showAddItem(final boolean show) {
		if (addItem != null)
			addItem.setVisible(show);
	}

	/**
	 * Show/hide add menu item
	 */
	protected void updateAddItem() {
		showAddItem(adapter.getSelectedCount() > 0);
	}

	/**
	 * Add selected songs to the queue and finish this activity when complete
	 */
	protected void queueSelectedSongs() {
		if (adapter.getSelectedCount() < 1)
			return;

		showAddItem(false);

		final Song[] albums = adapter.getSelectedAlbums();
		final Song[] songs = adapter.getSelectedSongs();

		String message;
		if (songs.length > 1 || albums.length != 0)
			message = MessageFormat.format(
					getString(string.adding_songs_to_queue), songs.length);
		else
			message = getString(string.adding_song_to_queue);
		Toaster.showShort(SongViewActivity.this, message);

		new QueueSongsTask(service) {

			@Override
			protected IOException doInBackground(Song... params) {
				if (albums.length > 0) {
					Set<Song> albumSongs = new LinkedHashSet<Song>();
					for (Song album : albums)
						try {
							for (Song song : service.get().getSongs(
									album.artist, album.album))
								albumSongs.add(song);
						} catch (IOException e) {
							return e;
						}
					if (!albumSongs.isEmpty()) {
						for (Song song : params)
							albumSongs.add(song);
						params = albumSongs
								.toArray(new Song[albumSongs.size()]);
					}
				}

				return super.doInBackground(params);
			}

			@Override
			protected void onPostExecute(IOException result) {
				super.onPostExecute(result);

				if (result != null) {
					Toaster.showLong(SongViewActivity.this,
							string.queueing_failed);
					showAddItem(true);
				} else {
					sendBroadcast(new Intent(ACTION_QUEUE));
					setResult(RESULT_OK);
					finish();
				}
			}
		}.execute(songs);
	}

	private void updateTitle() {
		String title;
		int count = adapter.getSelectedCount();
		if (count > 0)
			title = MessageFormat.format(getString(string.multiple_selected),
					count);
		else if (count == 1)
			title = getString(string.single_selected);
		else
			title = getString(string.search);
		getSupportActionBar().setTitle(title);
	}

	public void onItemClick(AdapterView<?> parent, View view, int position,
			long itemId) {
		adapter.toggleSelection(position);
		updateTitle();
		showAddItem(adapter.getSelectedCount() > 0);
		adapter.update(position, view, parent.getItemAtPosition(position));
	}
}
