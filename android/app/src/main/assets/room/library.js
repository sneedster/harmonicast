// Shared library browsing for guest and room-display pages.
window.createRoomLibraryBrowser = function ({api, results, showTracks, notify, onSearchDone}) {
  let generation = 0, snapshot = null;
  const cache = new Map(), jobs = [];
  let active = 0;
  function drain() { while(active < 3 && jobs.length) { active++; jobs.shift()().finally(() => { active--; drain(); }); } }
  function art(album, image, version) {
    if (!cache.has(album.id)) {
      if(cache.size >= 48) cache.delete(cache.keys().next().value);
      cache.set(album.id, new Promise(resolve => { jobs.push(async () => {
        if(version !== generation) { cache.delete(album.id); resolve(null); return; }
        try { const data=await api('/v1/artwork?album='+encodeURIComponent(album.id)); resolve(typeof data.image==='string' && /^data:image\/(jpeg|png|webp);base64,/.test(data.image) ? data.image : null); }
        catch (_) {cache.delete(album.id);resolve(null);}
      }); drain(); }));
    }
    cache.get(album.id).then(src => { if(src && version===generation) { image.src=src; image.hidden=false; } });
    image.onerror=()=>{image.hidden=true;};
  }
  function albums(data, restoreId) {
    results.replaceChildren();
    const version = generation;
    const heading=document.createElement('li');heading.className='album-heading';heading.textContent=(data.artist || 'Library')+' · Albums';results.appendChild(heading);
    for(const album of data.items) {
      const row=document.createElement('li');row.className='library-album';
      const button=document.createElement('button');button.type='button';button.className='library-album-button secondary';button.setAttribute('aria-label','View tracks on '+album.title);
      const cover=document.createElement('span');cover.className='catalog-cover';cover.setAttribute('aria-hidden','true');cover.textContent='♫';
      const image=document.createElement('img');image.alt='';image.hidden=true;cover.appendChild(image);
      const text=document.createElement('span');text.className='track';
      const title=document.createElement('strong');title.textContent=album.title;
      const year=document.createElement('span');year.textContent=[album.year || 'Year unknown',album.artist].filter(Boolean).join(' · ');
      text.append(title,year);button.append(cover,text);row.appendChild(button);results.appendChild(row);
      art(album,image,version);
      button.addEventListener('click',async()=>{
        const token=++generation;notify('Loading album…');button.disabled=true;
        try {
          const songs=await api('/v1/library/album?id='+encodeURIComponent(album.id));
          if(token!==generation)return;
          showTracks(songs);notify(songs.length?'':'This album has no playable tracks.');
          const entry=document.createElement('li');entry.className='album-heading';
          const back=document.createElement('button');back.type='button';back.className='secondary';back.textContent='Back to albums';
          back.addEventListener('click',()=>{++generation;albums(data,album.id);notify('');});entry.appendChild(back);results.prepend(entry);back.focus();
        }catch(e){if(token===generation){notify(e.message);button.disabled=false;}}
      });
      if(restoreId===album.id)button.focus();
    }
  }
  return {
    clear(){++generation;snapshot=null;results.replaceChildren();},
    async search(query){
      const token=++generation;notify('Searching…');
      try {
        const data=await api('/v1/library/search?q='+encodeURIComponent(query));
        if(token!==generation)return;
        snapshot=data;
        if(data.kind==='albums')albums(data);else showTracks(data.items);
        notify(data.items.length?'':'No matching music.');
        onSearchDone(query,data.items);
      }catch(e){if(token===generation)notify(e.message);}
    }
  };
};
