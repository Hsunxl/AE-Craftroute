package com.ae2vm.addon.route;

import java.util.*;

/** Presentation-only pruning: never edits the ingredients of an encodable recipe. */
public final class RouteBranchEdits {
    private final Set<String> hidden=new HashSet<>(),collapsed=new HashSet<>();
    private final Map<String,Set<String>> visible=new HashMap<>();
    private final Map<String,Set<String>> hiddenKeys=new HashMap<>();
    private final Set<String> hiddenMaterials=new HashSet<>();
    private final Set<String> hiddenChildren=new HashSet<>();
    public record Snapshot(Set<String> hidden,Set<String> collapsed,Map<String,Set<String>> visible,Map<String,Set<String>> hiddenKeys,Set<String> hiddenMaterials,Set<String> hiddenChildren) {}
    public Snapshot snapshot() {
        var copy=new HashMap<String,Set<String>>();visible.forEach((p,v)->copy.put(p,Set.copyOf(v)));
        var hk=new HashMap<String,Set<String>>();hiddenKeys.forEach((p,v)->hk.put(p,Set.copyOf(v)));
        return new Snapshot(Set.copyOf(hidden),Set.copyOf(collapsed),Map.copyOf(copy),Map.copyOf(hk),Set.copyOf(hiddenMaterials),Set.copyOf(hiddenChildren));
    }
    public void restore(Snapshot s) {clear();hidden.addAll(s.hidden);collapsed.addAll(s.collapsed);s.visible.forEach((p,v)->visible.put(p,new HashSet<>(v)));s.hiddenKeys.forEach((p,v)->hiddenKeys.put(p,new HashSet<>(v)));hiddenMaterials.addAll(s.hiddenMaterials);hiddenChildren.addAll(s.hiddenChildren);}
    public void clear() {hidden.clear();collapsed.clear();visible.clear();hiddenKeys.clear();hiddenMaterials.clear();hiddenChildren.clear();}
    public boolean expanded(String path) {return !collapsed.contains(path);}
    public boolean shown(String parent,String child,String key) {
        if(hidden.contains(child))return false;
        var hk=hiddenKeys.get(parent);
        if(hk!=null && hk.contains(key))return false;
        return !visible.containsKey(parent)||visible.get(parent).contains(key);
    }
    public void collapse(String path) {collapsed.add(path);}
    public void hide(String path) {hidden.add(path);}
    public void hide(String parent,String key) {hiddenKeys.computeIfAbsent(parent,p->new HashSet<>()).add(key);}
    public void hideMaterial(String key) {hiddenMaterials.add(key);}
    public void showMaterial(String key) {hiddenMaterials.remove(key);}
    public boolean materialHidden(String key) {return hiddenMaterials.contains(key);}
    public void hideChild(String parent,int index) {hiddenChildren.add(parent+"#"+index);}
    public void showChild(String parent,int index) {hiddenChildren.remove(parent+"#"+index);}
    public boolean childHidden(String parent,int index) {return hiddenChildren.contains(parent+"#"+index);}
    public void show(String path) {hidden.remove(path);}
    public void expandAll(String path) {collapsed.remove(path);visible.remove(path);hidden.removeIf(p->p.startsWith(path+"/"));hiddenKeys.keySet().removeIf(p->p.startsWith(path+"/"));hiddenKeys.remove(path);hiddenChildren.removeIf(c->c.startsWith(path+"#")||c.startsWith(path+"/"));}
    public void reveal(String path,String key,boolean sameRecipe) {
        boolean wasCollapsed=collapsed.remove(path);
        if(sameRecipe && !wasCollapsed && !visible.containsKey(path))return;
        if(!sameRecipe || !visible.containsKey(path))visible.put(path,new HashSet<>());
        visible.get(path).add(key);
    }
}
