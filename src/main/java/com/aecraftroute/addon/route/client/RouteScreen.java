package com.aecraftroute.addon.route.client;

import appeng.api.stacks.*;
import com.aecraftroute.addon.route.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * AE-styled workbench. Local branch edits and server-side route policies are distinct:
 * choosing/dragging a recipe edits the canvas, and Apply explicitly changes the plan.
 * Encoding consumes a real network blank pattern after server validation.
 */
public final class RouteScreen extends Screen {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(RouteScreen.class);
    private static final int BG = 0xFFCBCCD4;
    private static final int BAND = 0xFFCBCCD4;
    private static final int PANEL = 0xFFCBCCD4;
    private static final int PANEL_ALT = 0xFF9A9FB4;
    private static final int CANVAS_BG = 0xFF9A9FB4;
    private static final int CANVAS_EDGE = 0xFF696D88;
    private static final int CANVAS_GRID = 0xFF878FA5;
    private static final int BUTTON_FACE = 0xFFADB0C4;
    private static final int BUTTON_HOVER = 0xFFC6C6D4;
    private static final int BORDER = 0xFF373737;
    private static final int TEXT = 0xFF2D2F38;
    private static final int MUTED = 0xFF4B4F63;
    private static final int FAINT = 0xFF6A6E82;
    private static final int ACCENT = 0xFF3E6B8A;
    private static final int GOLD = 0xFF7A5A2E;
    private static final int AMBER = 0xFF7A5A2E;
    private static final int BLUE = 0xFF3E6B8A;
    private static final int RED = 0xFF8E2F2F;
    private static final int PURPLE = 0xFF5A6E85;
    private static final int ROW_H = 32;
    private static final int MAX_QUERY_IN_FLIGHT = 8;

    private final Screen parent;
    private AEKey originalTarget;
    private long originalAmount;
    private boolean fromCrafting;
    private final boolean canModify;
    private RouteWorkbenchPanel workbench;
    private int workbenchTicks,lastWorkbenchSequence;
    private boolean narrowInspector;
    private boolean narrow() {return windowBounds().width()<320;}
    private boolean workbenchVisible() {return !narrow() || narrowInspector;}
    private boolean nativeRecipeVisible() {return (!narrow() || narrowInspector) && jeiTab && browser!=null && (selectedRecipe==null || selectedRecipe.draft);}
    private String encodingLabel(MNode node) {
        var value=nativeRecipeVisible()?browser.currentDraft():branchDrafts.get(node.path);
        if(RouteEmcRules.isEmc(value))return "EMC 路线（无需样板）";
        return value!=null && RouteNetwork.GSON.toJson(value).equals(armedEncoding)?"确认编码":"生成样板";
    }
    private void encodeSelected() {
        var node=activeMaterial();
        var value=nativeRecipeVisible()?browser.currentDraft():node==null?null:branchDrafts.get(node.path);
        if(value==null) {status="先选择并展开一张 JEI 配方";return;}
        if(RouteEmcRules.isEmc(value)) {status="EMC 路线已用于画布规划；请在 ProjectE 转换设备取出物品，无需 AE2 样板";return;}
        if(!RouteNetwork.GSON.toJson(value).equals(armedEncoding)) {
            armedEncoding=RouteNetwork.GSON.toJson(value);status="编码包含完整 JEI 材料（含画布未显示的材料）；再次点击确认";return;
        }
        armedEncoding=null;send("encode",node.material.renderKey,RouteNetwork.GSON.toJson(value));
    }
    public RouteWindow windowBounds() {
        var mc=Minecraft.getInstance();
        int sw=width>0?width:mc.getWindow().getGuiScaledWidth();
        int sh=height>0?height:mc.getWindow().getGuiScaledHeight();
        return RouteWindow.of(sw,sh);
    }
    private RouteLayout uiLayout() {return windowBounds().layout();}
    private boolean compact() {return uiLayout().compact();}
    private boolean usable() {return uiLayout().usable();}
    private int inspectorBottom() {return workbench==null?canvasBottom:workbench.top()-5;}
    private void switchMode(boolean modify) {
        if(modify && !canModify) {status="请从自动合成确认界面进入修改模式";return;}
        if(modify) {
            if(send("restore",null,"")==0)return;
            fromCrafting=true;
        } else {fromCrafting=false;checkpoint();setTarget(null,0,"新建","");}
        buildWidgets();
    }
    private final Map<String,RouteView.Choice> originalChoices;
    private String armedEncoding;
    private boolean manualBuild;
    private boolean expandingJei,jeiTreeMode;
    private RouteRecipeBrowser expansionBrowser;
    private final Set<String> expansionVisited=new HashSet<>();
    private int expansionErrors;
    private boolean jeiTab = true;
    private boolean initialized;
    private RouteRecipeBrowser browser;
    private final RouteRecipeHover recipeHover=new RouteRecipeHover();
    private final Map<String, RouteView.Choice> branches = new LinkedHashMap<>();
    private final Map<String,String> appliedPathChoices=new LinkedHashMap<>();
    private final Map<String,RouteView.Draft> appliedPathDrafts=new LinkedHashMap<>();
    private final Map<String, RouteView.Draft> branchDrafts = new LinkedHashMap<>();
    private final Deque<EditState> undo = new ArrayDeque<>();
    private final RouteBranchEdits branchEdits=new RouteBranchEdits();
    private String quantityPath;
    private String quantityText = "";
    private boolean quantityFresh;
    private final List<ActionHit> nodeMenuHits=new ArrayList<>();
    private boolean nodeMenu;
    private boolean nodeMenuRecipes;
    private RouteNodeMenu.Page nodeMenuSection=RouteNodeMenu.Page.ROOT,recipeReturnSection=RouteNodeMenu.Page.ROOT;
    private int nodeMenuX,nodeMenuY,nodeMenuPage,nodeMenuLeft,nodeMenuTop,nodeMenuWidth,nodeMenuHeight;
    private MNode nodeMenuTarget;
    private long ticks;
    private void openNodeMenu(MNode node,double mx,double my,boolean recipes) {
        if(browser!=null)browser.clearMachineOverlay();
        if(itemId!=null)itemId.setFocused(false);cancelQuantity();
        selectedNode=node;selectedKey=node.material.key;selectedRecipe=null;jeiTab=true;
        if(browser!=null)browser.select(node.material.renderKey);
        nodeMenu=true;nodeMenuRecipes=recipes;nodeMenuPage=0;nodeMenuTarget=node;
        nodeMenuSection=RouteNodeMenu.Page.ROOT;recipeReturnSection=RouteNodeMenu.Page.ROOT;
        nodeMenuHits.clear();
        nodeMenuX=(int)mx;nodeMenuY=(int)my;
    }
    private void removeBranch(boolean wholeNode) { removeBranch(nodeMenuTarget, wholeNode); }
    private void removeBranch(MNode node, boolean wholeNode) {
        if(node==null)return;
        checkpoint();nodeMenu=false;nodeMenuTarget=null;
        if(wholeNode && node==root) {
            target=null;targetAmount=0;targetKeyId=null;source="手动";path="";
            branchEdits.clear();branches.clear();branchDrafts.clear();draft=null;draftChoice=null;
            materials.clear();offsets.clear();pending.clear();
            selectedNode=null;selectedRecipe=null;selectedKey=null;
            setFieldText("");
            rebuildGraph();
            status="已移除最终产物节点；输入新目标，或点“新建”继续";
            return;
        }
        if(wholeNode) {
            branchEdits.hide(node.path);
            String parent = node.path.contains("/") ? node.path.substring(0, node.path.lastIndexOf('/')) : "";
            int slash=node.path.lastIndexOf('/'), hash=node.path.lastIndexOf('#');
            int slot=-1;
            if(hash>slash && hash+1<node.path.length()) try {slot=Integer.parseInt(node.path.substring(hash+1));} catch(NumberFormatException ignored) {}
            // Prune this occurrence only: another branch may need the same material.
            if(slot>=0)branchEdits.hideChild(parent,slot);
            LOGGER.info("Removed material node: path={}, key={}, parent={}, slot={}",node.path,node.material.key,parent,slot);
        } else branchEdits.collapse(node.path);
        branches.keySet().removeIf(p->p.equals(node.path)||p.startsWith(node.path+"/"));
        branchDrafts.keySet().removeIf(p->p.equals(node.path)||p.startsWith(node.path+"/"));
        rebuildGraph();status=wholeNode && node!=root?"已移除节点及其下级；原配方完整材料不变，可撤销":"已移除下级分支，保留物品节点";
    }
    private void drawNodeMenu(GuiGraphics g,int mx,int my) {
        nodeMenuHits.clear();if(!nodeMenu)return;
        var current=nodeMenuTarget==null?null:findByPath(nodeMenuTarget.path);
        if(current==null || !current.material.key.equals(nodeMenuTarget.material.key)) {nodeMenu=false;return;}
        nodeMenuTarget=current;
        var entries=RouteNodeMenu.entries(nodeMenuSection,new RouteNodeMenu.Context(branchEdits.expanded(current.path),
                current.recipe!=null,current==root,current.cycle,false));
        var options=browser==null?List.<RouteRecipeBrowser.Option>of():browser.options();
        int capacity=nodeMenuCapacity();
        var page=RoutePopupPage.of(options.size(),capacity,nodeMenuPage);
        nodeMenuPage=page.page();
        int rows=nodeMenuRecipes?page.rows():0,w=Math.max(1,Math.min(240,canvasWidth()-8));
        int h=nodeMenuRecipes?42+rows*18:24+entries.size()*18;
        int x=Math.max(canvasLeft,Math.min(nodeMenuX,canvasRight-w));
        int y=Math.max(canvasTop,Math.min(nodeMenuY,canvasBottom-h));
        nodeMenuLeft=x;nodeMenuTop=y;nodeMenuWidth=w;nodeMenuHeight=h;
        g.pose().pushPose();g.pose().translate(0,0,1000);
        g.fill(x,y,x+w,y+h,0xFFCBCCD4);frame(g,x,y,w,h,ACCENT);
        text(g,nodeMenuRecipes?"选择配方 · "+page.range(options.size()):RouteNodeMenu.title(nodeMenuSection),x+4,y+5,w-8,TEXT);
        for(int row=0;row<rows;row++) {
            int i=page.start()+row;
            var option=options.get(i);int ry=y+20+row*18;
            menuAction(g,x,ry,w,"",()->{
                browser.selectIndex(option.index());var value=browser.currentDraft();
                nodeMenu=false;if(value!=null)expandDraft(value);else status="此 JEI 配方无法转换为材料分支";
            });
            browser.renderOption(g,option.index(),x+2,ry,w-4,mx,my);
            if(browser.optionMachineContains(option.index(),x+2,ry,w-4,mx,my))
                nodeMenuHits.add(0,new ActionHit(x+2,ry,x+20,ry+18,"设备",()->browser.showOptionMachine(option.index())));
        }
        int by=y+20+rows*18;
        if(nodeMenuRecipes) {
            int part=w/3;
            menuAction(g,x,by,part,"‹ 返回",this::backNodeMenu);
            menuAction(g,x+part,by,part,"‹ 上页",()->moveNodeMenuPage(-1));
            menuAction(g,x+part*2,by,w-part*2,"下页 ›",()->moveNodeMenuPage(1));
        } else {
            for(int i=0;i<entries.size();i++) {
                var entry=entries.get(i);int ey=by+i*18;
                if(entry.enabled())menuAction(g,x,ey,w,entry.label(),()->runNodeMenu(entry.action()));
                else text(g,entry.label(),x+4,ey+5,w-8,FAINT);
            }
        }
        g.pose().popPose();
    }
    private void menuSection(RouteNodeMenu.Page page) {nodeMenuSection=page;nodeMenuRecipes=false;nodeMenuHits.clear();}
    private void backNodeMenu() {
        if(nodeMenuRecipes) {menuSection(recipeReturnSection);return;}
        if(nodeMenuSection!=RouteNodeMenu.Page.ROOT)menuSection(RouteNodeMenu.parent(nodeMenuSection));else nodeMenu=false;
    }

    private void runNodeMenu(RouteNodeMenu.Action action) {
        var current=nodeMenuTarget==null?null:findByPath(nodeMenuTarget.path);
        if(current==null || !current.material.key.equals(nodeMenuTarget.material.key)) {nodeMenu=false;return;}
        nodeMenuTarget=current;selectedNode=current;selectedKey=current.material.key;
        switch(action) {
            case BRANCH -> menuSection(RouteNodeMenu.Page.BRANCH);case POLICY -> menuSection(RouteNodeMenu.Page.POLICY);
            case QUANTITY -> menuSection(RouteNodeMenu.Page.QUANTITY);case REMOVE -> menuSection(RouteNodeMenu.Page.REMOVE);
            case HELP -> menuSection(RouteNodeMenu.Page.HELP);case BACK -> backNodeMenu();case TOGGLE -> toggleBranch();
            case RECIPES -> {recipeReturnSection=nodeMenuSection;nodeMenuRecipes=true;nodeMenuPage=0;nodeMenuHits.clear();}
            case STOCK -> {nodeMenu=false;stockSelected();}case AUTO -> {nodeMenu=false;automaticSelected();}
            case EDIT -> {nodeMenu=false;startQuantityEdit(nodeMenuTarget);}case RESET -> {}
            case CLEAR -> removeBranch(nodeMenuTarget,false);case DELETE -> removeBranch(nodeMenuTarget,true);case NONE -> {}
        }
    }
    private int nodeMenuCapacity() {return RoutePopupPage.capacity(canvasBottom-canvasTop,42,5);}
    private void moveNodeMenuPage(int delta) {
        int count=browser==null?0:browser.options().size();
        nodeMenuPage=RoutePopupPage.of(count,nodeMenuCapacity(),nodeMenuPage).move(count,delta).page();
        nodeMenuHits.clear();
    }
    private void menuAction(GuiGraphics g,int x,int y,int w,String label,Runnable run) {
        g.fill(x+1,y,x+w-1,y+17,0xFFADB0C4);text(g,label,x+4,y+5,w-8,TEXT);
        nodeMenuHits.add(new ActionHit(x,y,x+w,y+18,label,run));
    }
    private void dropRecipeIngredient(RouteView.Draft value,AEKey ingredient,double mx,double my) {
        LOGGER.info("Route canvas ingredient drop: recipe={}, ingredient={}, parent={}",value.recipeId(),ingredient.getDisplayName().getString(),activeMaterial()==null?"none":activeMaterial().material.name);
        if(!inside(mx,my,canvasLeft,canvasTop,canvasWidth(),canvasBottom-canvasTop)
                || workbenchVisible() && workbench!=null && workbench.contains(mx,my)) {status="拖入画布空白处或目标物品节点，才会创建材料分支";return;}
        var node=activeMaterial();var hit=materialAt(mx,my);
        var choice=draftChoice(value);
        if(hit!=null && RouteGraphMath.outputAmount(choice,hit.material.key)>0)node=hit;
        if(node==null || RouteGraphMath.outputAmount(choice,node.material.key)<=0) {status="该配方不产出所选节点，不能连接";return;}
        String ingredientId=keyId(ingredient);
        if(choice.inputs().stream().noneMatch(stack->stack.key().equals(ingredientId))) {status="不是此配方的可消耗材料";return;}
        checkpoint();
        var previousDraft=branchDrafts.get(node.path);
        boolean same=RecipeDraftImport.sameRecipe(previousDraft,value);
        if(!same) {
            String prefix=node.path+"/";
            branches.keySet().removeIf(p->p.startsWith(prefix));branchDrafts.keySet().removeIf(p->p.startsWith(prefix));
        }
        branches.put(node.path,choice);branchDrafts.put(node.path,value);
        branchEdits.reveal(node.path,ingredientId,same);
        branchEdits.showMaterial(ingredientId);
        var inputs=RouteGraphMath.groupedInputs(choice.inputs());
        for(int i=0;i<inputs.size();i++)if(inputs.get(i).key().equals(ingredientId)) {
            branchEdits.show(RouteGraphMath.childPath(node.path,i));
            branchEdits.showChild(node.path,i);
        }
        selectedNode=node;selectedKey=node.material.key;selectedRecipe=null;
        rebuildGraph();
        for(var child:selectedNode.children)if(child.material.key.equals(ingredientId)) {
            applyGeometry(RouteTreeGeometry.drag(graphGeometry(),child.path,(int)contentX(mx)-child.x-child.w/2,
                    (int)contentY(my)-child.y-child.h/2));saveGeometryOffsets();break;
        }
        status="已连接拖入的材料；继续拖其他材料，或在节点菜单选择配方展开全部。编码仍使用完整配方";
    }
    private record EditState(AEKey target, long amount, boolean manual,
                             Map<String, RouteView.Choice> branches, Map<String, RouteView.Draft> drafts,
                             RouteBranchEdits.Snapshot edits, Map<String, int[]> offsets) {}
    /** Copy node offsets so undo restores the layout and later drags cannot mutate a snapshot. */
    private Map<String, int[]> copyOffsets() {
        var copy = new HashMap<String, int[]>();
        offsets.forEach((path, offset) -> copy.put(path, offset.clone()));
        return copy;
    }
    private EditState captureState() {
        return new EditState(target, targetAmount, manualBuild, new LinkedHashMap<>(branches),
                new LinkedHashMap<>(branchDrafts), branchEdits.snapshot(), copyOffsets());
    }
    private void pushUndo(EditState state) {
        if (state == null) return;
        if (undo.size() >= 64) undo.removeFirst();
        undo.addLast(state);
    }
    private void checkpoint() { expandingJei=false;expansionBrowser=null;pushUndo(captureState()); }
    private void undoEdit() {
        if (undo.isEmpty()) { status="没有可撤销的画布操作"; return; }
        expandingJei=false;jeiTreeMode=false;expansionBrowser=null;
        var state=undo.removeLast();branchEdits.restore(state.edits);nodeMenu=false;
        target=state.target; targetAmount=state.amount; targetKeyId=keyId(target); manualBuild=state.manual;
        branches.clear(); branches.putAll(state.branches); branchDrafts.clear(); branchDrafts.putAll(state.drafts);
        offsets.clear(); state.offsets.forEach((path, offset) -> offsets.put(path, offset.clone()));
        cancelQuantity();
        selectedNode=null; selectedRecipe=null; setFieldText(targetName(target)); rebuildGraph(); status="已退回上一步（仅画布，不回收已生成的样板）";
    }
    private void startQuantityEdit(MNode node) {
        if (!quantityEditable(node)) { status = "仅最终产物可修改数量；其余材料由配方自动推导"; return; }
        if(itemId!=null)itemId.setFocused(false);
        quantityPath = node.path;
        quantityText = String.valueOf(node.demand);
        quantityFresh = true;
        selectedNode = node; selectedKey = node.material.key; selectedRecipe = null;
        status = "输入或滚轮调整最终产物数量：Enter 应用，Esc 取消；Shift 滚轮单个调整";
    }
    private boolean quantityEditable(MNode node) {return node!=null && RouteQuantityField.editable(node==root,node.cycle);}
    private void cancelQuantity() { quantityPath = null; quantityText = ""; quantityFresh = false; }
    private void commitQuantity() {
        String path = quantityPath, text = quantityText;
        boolean fresh = quantityFresh;
        cancelQuantity();
        MNode node = path == null ? null : findByPath(path);
        if (node == null) { status = "节点已变化，数量未修改"; return; }
        if (!quantityEditable(node)) { status = "仅最终产物可修改数量"; return; }
        if (fresh) { status = "数量未修改"; return; }
        Long value = RouteGraphMath.parseDemand(text);
        if (value == null) { status = "最终产物数量不能留空"; return; }
        checkpoint(); targetAmount = value; rebuildGraph(); status = "已修改最终产物数量";
    }
    private void adjustQuantity(MNode node, int direction, boolean fine) {
        if (!quantityEditable(node)) { status = "仅最终产物可修改数量"; return; }
        long output = node.recipe == null ? 0 : RouteGraphMath.outputAmount(node.recipe.choice, node.material.key);
        if(node.path.equals(quantityPath)) {
            quantityText=RouteQuantityField.stepText(quantityText,node.demand,output,direction,fine);quantityFresh=false;
            status="待确认数量 "+quantityText+"；Enter 应用，Esc 取消";return;
        }
        long next = RouteGraphMath.stepDemand(node.demand, output, direction, fine);
        if (next == node.demand) return;
        checkpoint();
        targetAmount = next;
        selectedNode = node; selectedKey = node.material.key; selectedRecipe = null;
        rebuildGraph();
        status = "已调整最终产物数量";
    }
    private void clearManual() {
        expandingJei=false;jeiTreeMode=false;expansionBrowser=null;checkpoint(); branchEdits.clear();nodeMenu=false;manualBuild=true; branches.clear(); branchDrafts.clear(); draftChoice=null; draft=null;
        offsets.clear();  selectedNode=null; selectedRecipe=null; rebuildGraph(); status="已清空分支，保留最终产物起点";
    }
    /** Restore the automatic tree layout without touching selected recipes or materials. */
    private void autoArrange() {
        if (root == null) { status="还没有可整理的材料树；先设置目标并选择配方"; return; }
        checkpoint();
        offsets.clear();
        nodeMenu=false;
        fitCanvas();
        status="已自动整理：清除手动挪动的位置，按树形重新排列并适配窗口（配方与材料不变）";
    }
    private void expandJeiTree() {
        if(target==null) {status="先设置最终产物";return;}
        if(RouteClient.recipeBrowser==null) {status="JEI 不可用，无法展开配方";return;}
        checkpoint();
        try {expansionBrowser=RouteClient.recipeBrowser.get();}
        catch(RuntimeException error){status="JEI 配方读取器不可用";return;}
        if(expansionBrowser==null){status="JEI 配方读取器不可用";return;}
        branchEdits.clear();nodeMenu=false;manualBuild=true;jeiTreeMode=true;expandingJei=true;
        // Keep user-selected JEI drafts, never import existing network pattern identity.
        branches.keySet().removeIf(p->!branchDrafts.containsKey(p));
        expansionVisited.clear();expansionErrors=0;
        rebuildGraph();status="正在展开 JEI 配方；仅构建画布，不绑定样板";
    }
    private void restoreTree() { expandingJei=false;jeiTreeMode=false;expansionBrowser=null;send("restore",null,""); }
    private void clearAppliedCanvas() { send("clearcanvas",null,""); }
    public int contentRight() { return uiLayout().contentRight(); }
    public void showRecipes(AEKey key) { if(narrow()){narrowInspector=true;buildWidgets();}jeiTab=true; if(browser!=null)browser.select(key); }
    public void expandDraft(RouteView.Draft value) {
        var node=activeMaterial();
        if(node==null) { setTarget(value.outputs().get(0).decode(Minecraft.getInstance().level.registryAccess()),1,"JEI",""); node=root; }
        var choice=draftChoice(value);
        if(node==null || RouteGraphMath.outputAmount(choice,node.material.key)<=0) { status="此配方不产出选中的材料"; return; }
        checkpoint(); branchEdits.expandAll(node.path);
        if(!choice.equals(branches.get(node.path))) {
            String prefix=node.path+"/";
            branches.keySet().removeIf(p->p.startsWith(prefix));branchDrafts.keySet().removeIf(p->p.startsWith(prefix));
        }
        branches.put(node.path,choice); branchDrafts.put(node.path,value);
        rebuildGraph(); jeiTab=true; selectedRecipe=selectedNode==null?null:selectedNode.recipe;
        status=RouteEmcRules.isEmc(value)?"已选择 EMC 获取路线；画布显示 EMC 总消耗，请在转换设备取出":"已展开配方；点击下层材料继续创建分支";
    }
    private final int menuId;
    private AEKey target;
    private RouteView.Draft draft;
    private RouteView.Choice draftChoice;
    private long targetAmount;
    private String source;
    private String path;
    private String targetKeyId;
    private boolean centerPending = true;
    private boolean autoCenter = true;
    private boolean draftMode;

    private String selectedKey;
    private MNode selectedNode;
    private RNode selectedRecipe;
    private String status = "读取当前网络…";
    /** Server-computed verdict for the current target/amount; never a local estimate. */
    private RoutePlanView plan;
    private String lastPreviewSignature;
    private EditBox itemId;
    /** Retry state for the unanswered "begin"; see {@link BeginRetryState}. */
    private final BeginRetryState beginRetry = new BeginRetryState();
    private java.util.function.Consumer<RouteNetwork.Response> previousClientHandler;
    private java.util.function.Consumer<RouteNetwork.Response> clientHandler;

    private int panelW, panelLeft, panelRight, canvasLeft, canvasRight, toolbarY, canvasTop, canvasBottom;
    private int panX, panY;
    private float zoom = 1.0F;
    private boolean fitPending;
    private int canvasMouseX=-1,canvasMouseY=-1;
    private Map<AEKey,Long> startupByKey=Map.of();
    private int choiceScroll;
    private static int nextSequence;
    private int latestPlanSequence;
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Deque<RouteNetwork.Request> canvasChunks=new ArrayDeque<>();
    private int canvasChunkSequence;
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final Map<String, int[]> offsets = new HashMap<>();
    private final List<MNode> materialNodes = new ArrayList<>();
    private final List<RNode> recipeNodes = new ArrayList<>();
    private final List<ChoiceHit> choiceHits = new ArrayList<>();
    private final List<ActionHit> actionHits = new ArrayList<>();
    private final List<SuggestionHit> suggestionHits = new ArrayList<>();
    private List<Item> suggestions = List.of();
    private record SearchItem(Item item,String name,String id,String lowerName,String lowerId) {}
    private List<SearchItem> searchIndex=List.of();
    private net.minecraft.locale.Language searchLanguage;
    private int searchRegistrySize;
    private int suggestionPage;
    private int suggestionX, suggestionY, suggestionW, suggestionH;
    private boolean suppressSuggestions;
    private MNode root;

    private MNode wireFrom;
    private double wireX, wireY;
    private MNode draggedMaterial;
    private List<RouteTreeGeometry.Box> dragGeometry=List.of();
    private final RouteNativeMachines nativeMachines=new RouteNativeMachines();
    private EditState pendingDragState;
    private double dragMouseX, dragMouseY;
    private int dragNodeX, dragNodeY;
    private boolean nodeDragMoved;
    private boolean panning;
    private double panMouseX, panMouseY;
    private int panStartX, panStartY;

    public RouteScreen(Screen parent, AEKey target, RouteView.Draft draft,
                       long targetAmount, String source, String path) {
        super(Component.literal("AE2 合成树画布"));
        this.parent = parent;
        var player=Minecraft.getInstance().player;
        var planned=player!=null && player.containerMenu instanceof appeng.menu.me.crafting.CraftConfirmMenu confirm?RouteTreeSnapshot.output(confirm):null;
        this.originalTarget=planned==null?target:planned.what();
        this.originalAmount=planned==null?Math.max(0,targetAmount):planned.amount();
        this.canModify=Minecraft.getInstance().player!=null && Minecraft.getInstance().player.containerMenu instanceof appeng.menu.me.crafting.CraftConfirmMenu;
        this.fromCrafting=canModify && draft==null;
        this.manualBuild=!fromCrafting;
        var mc=Minecraft.getInstance();
        this.originalChoices=canModify && mc.player!=null && mc.player.containerMenu instanceof appeng.menu.me.crafting.CraftConfirmMenu confirm
                ? RouteTreeSnapshot.read(confirm,mc.level.registryAccess()):Map.of();
        if(RouteClient.recipeBrowser!=null)browser=RouteClient.recipeBrowser.get();
        this.target = target;
        this.draft = draft;
        this.targetAmount = Math.max(0, targetAmount);
        this.source = source == null || source.isBlank() ? "手动" : source;
        if(fromCrafting && originalChoices.isEmpty())this.source+="（网络重建预览）";
        this.path = path == null ? "" : path;
        this.menuId = Minecraft.getInstance().player == null ? -1
                : Minecraft.getInstance().player.containerMenu.containerId;
        this.draftMode = draft != null;
        this.draftChoice = draftChoice(draft);
        this.targetKeyId = target == null ? null : keyId(target);
        this.selectedKey = targetKeyId;
        LOGGER.info("Route canvas opened: menu={}, modify={}, target={}, amount={}, importedRecipes={}",menuId,fromCrafting,targetName(target),targetAmount,originalChoices.size());
    }

    private static RouteView.Choice draftChoice(RouteView.Draft draft) {
        if (draft == null || draft.outputs().isEmpty()) return null;
        return new RouteView.Choice("jei:" + draft.recipeId(), draft.inputs(), draft.outputs(), false);
    }

    @Override
    protected void init() {
        recipeHover.clear();
        layout();
        if(workbench==null)workbench=new RouteWorkbenchPanel((action,argument)->send(action,null,argument)!=0);
        workbench.layout(panelLeft+6,panelRight-panelLeft-14,canvasBottom,canvasLeft,canvasWidth(),canvasTop,compact(),uiLayout().collapseCache());
        buildWidgets();
        status = target == null ? fromCrafting ? "正在等待合成确认页计划与服务端目标…"
                : "从合成树节点按 F8，或输入物品名称设置目标" : "正在读取可用配方…";
        if (target != null) {
            ensureMaterial(target);
            query(target);
        }
        if (!initialized && draft != null) {
            branches.put("root",draftChoice); branchDrafts.put("root",draft);
        }
        if(!initialized)pressBegin();
        initialized=true;
        workbench.refresh();
        rebuildGraph();
    }

    /**
     * Opens the session and asks the server for the authoritative confirmation target.
     * The answer can legitimately be empty: on a freshly opened confirmation screen the
     * plan may not have been computed yet, so {@code whatToCraft} is still unset on the
     * server. A single attempt would therefore leave the canvas without a target for its
     * whole lifetime, which is exactly what "target=" in the opening log line was.
     */
    private void pressBegin() {
        if(send("begin",null,"")>0) beginRetry.sent(ticks);
    }

    /**
     * Retries the target handshake while the canvas is still waiting for one. The server can
     * only report {@code whatToCraft} once the confirmation plan exists, and it says nothing
     * when it does not, so an unanswered attempt must be repeated rather than assumed final.
     */
    private void retryBeginIfDue() {
        if(beginRetry.retryAt()<0) return;
        if(target!=null) {beginRetry.answered();return;}
        if(beginRetry.exhausted()) {
            beginRetry.answered();
            if(Boolean.getBoolean("aecraftroute.qa"))
                LOGGER.info("Route QA give up authoritative begin target: menu={}, attempts={}",menuId,beginRetry.attempts());
            status="未能从合成确认页读取目标，请输入物品名称手动设置";
            return;
        }
        if(beginRetry.due(ticks)) pressBegin();
    }

    private void layout() {
        var layout=uiLayout();
        panelW=layout.panelWidth();panelRight=layout.panelRight();panelLeft=layout.panelLeft();
        canvasLeft=layout.canvasLeft();canvasRight=layout.canvasRight();
        toolbarY=layout.toolbarY();canvasTop=layout.canvasTop();canvasBottom=layout.canvasBottom();
    }

    private int canvasWidth() {
        return Math.max(1, canvasRight - canvasLeft);
    }

    private void buildWidgets() {
        clearWidgets();
        if (!usable()) {
            addRenderableWidget(new AeButton(windowBounds().left()+12, windowBounds().bottom()-40, 80, 20,
                    Component.literal("返回"), b -> onClose(), false));
            return;
        }
        int modeX=windowBounds().left()+(narrow()?8:compact()?54:104);
        var modify=new AeButton(modeX,windowBounds().top()+3,36,16,Component.literal("修改"),b->switchMode(true),fromCrafting);
        modify.active=canModify;addRenderableWidget(modify);
        addRenderableWidget(new AeButton(modeX+40,windowBounds().top()+3,36,16,Component.literal("新建"),b->switchMode(false),!fromCrafting));
        if(narrow())addRenderableWidget(new AeButton(modeX+80,windowBounds().top()+3,Math.max(40,windowBounds().width()-96),16,
                Component.literal(narrowInspector?"返回画布":"配方 / 缓存"),b->{narrowInspector=!narrowInspector;nodeMenu=false;if(browser!=null)browser.clearMachineOverlay();buildWidgets();},narrowInspector));
        buildToolbar();

        int fieldW = narrow()?windowBounds().width()-60:compact()?Math.max(54,Math.min(120,windowBounds().width()-188)):Math.min(180, Math.max(92, windowBounds().width() / 5));
        int fieldX = contentRight() - 8 - (compact()?38:48) - fieldW;
        int fieldY=windowBounds().top()+(narrow()?24:compact()?3:22);
        itemId = new EditBox(font, fieldX, fieldY, fieldW, 18, Component.literal("物品名称"));
        itemId.setMaxLength(256);
        itemId.setValue(target == null ? "" : targetName(target));
        if (target != null && !(target instanceof AEItemKey)) itemId.setValue("");
        itemId.setHint(Component.literal(target != null && !(target instanceof AEItemKey)
                ? "流体目标请从合成树或 JEI 打开" : "中文名，或 ae2:... 物品代码"));
        itemId.setResponder(this::onSearchChanged);
        addRenderableWidget(itemId);
        addRenderableWidget(new AeButton(contentRight() - (compact()?42:52), windowBounds().top()+(narrow()?24:compact()?3:21),
                compact()?34:44, 19, Component.literal(compact()?"目标":"设为目标"), b -> setTargetFromField(), false));
    }

    private void buildToolbar() {
        String[] labels=fromCrafting?new String[]{"还原","撤销","清空","撤画","展开","整理","应用","自动","库存","适应","重载"}
                :new String[]{"撤销","清空","撤画","展开","整理","应用","自动","库存","适应","重载"};
        String[] tips={"还原合成树","撤销上一次画布编辑","清空手动分支，保留最终产物","撤销已应用的强制画布，但保留逐材料锁定与禁用设置","从最终产物展开 JEI 配方，不读取或固定网络样板", "自动整理（Home）：清除手动位置并适应窗口",
                "整幅应用到合成","恢复自动路由","仅用现有库存：不再自动选配方，缺料即不可合成","适应窗口：保留手动位置","重载网络样板来源、AE 系列供应器及 JEI 配方映射；保留画布"};
        Runnable[] actions={this::restoreTree,this::undoEdit,this::clearManual,this::clearAppliedCanvas,this::expandJeiTree,this::autoArrange,this::applyCanvas,
                this::automaticSelected,this::stockSelected,this::fitView,this::reloadMappings};
        var cells=RouteToolbar.of(windowBounds().width()-16,labels.length);
        for(int i=0;i<labels.length;i++) {
            int action=i+(fromCrafting?0:1);
            addToolbarButton(labels[i],windowBounds().left()+8+i*cells.cellWidth(),cells.buttonWidth(),tips[action],actions[action]);
        }
    }
    private void fitView() { fitPending=true;rebuildGraph(); }
    private void reloadMappings() {
        nodeMenu=false;recipeHover.clear();if(browser!=null)browser.clearMachineOverlay();
        pending.entrySet().removeIf(e->e.getValue().action.equals("query"));
        for(var material:materials.values()) {material.reply=null;material.loading=false;material.error=null;}
        try {
            if(RouteClient.recipeBrowser!=null) {
                var fresh=RouteClient.recipeBrowser.get();var node=activeMaterial();
                fresh.invalidateMappings();
                if(node!=null)fresh.select(node.material.renderKey);
                if(selectedRecipe!=null && recipeMachineId(selectedRecipe)!=null)fresh.focusRecipe(recipeMachineId(selectedRecipe));
                browser=fresh;
            }
        } catch(RuntimeException e) {LOGGER.warn("JEI mapping reload failed",e);}
        if(target!=null)query(target);
        if(workbench!=null)workbench.refresh();
        rebuildGraph();status="已请求重载样板与供应器映射；回包后更新，保留画布";
    }
    private void toggleBranch() {
        var node=nodeMenuTarget;if(node==null)return;
        checkpoint();nodeMenu=false;
        branchEdits.toggleExpanded(node.path);rebuildGraph();
        status=branchEdits.expanded(node.path)?"已展开下级分支":"已折叠下级分支，配方与数量保留";
    }

    private void addToolbarButton(String label, int x, int w, String tip, Runnable action) {
        var button = new AeButton(x, toolbarY, w, 20, Component.literal(label), b -> action.run(), false);
        button.setTooltip(Tooltip.create(Component.literal(tip)));
        addRenderableWidget(button);
    }

    private String targetId(AEKey key) {
        if (key instanceof AEItemKey item) return item.getId().toString();
        return key.toString();
    }

    private String targetName(AEKey key) {
        return key == null ? "" : key.getDisplayName().getString();
    }

    private void onSearchChanged(String text) {
        if (suppressSuggestions) return;
        suggestionPage = 0;
        suggestions = searchItems(text);
    }

    private List<Item> searchItems(String text) {
        String query = text == null ? "" : text.trim();
        if (query.isEmpty()) return List.of();
        var language=net.minecraft.locale.Language.getInstance();
        if(searchIndex.isEmpty() || searchLanguage!=language || searchRegistrySize!=BuiltInRegistries.ITEM.size()) {
            var fresh=new ArrayList<SearchItem>();
            for(Item item:BuiltInRegistries.ITEM) {
                if(item==Items.AIR)continue;
                String name=new ItemStack(item).getHoverName().getString();
                String id=BuiltInRegistries.ITEM.getKey(item).toString();
                fresh.add(new SearchItem(item,name,id,name.toLowerCase(Locale.ROOT),id.toLowerCase(Locale.ROOT)));
            }
            searchIndex=List.copyOf(fresh);
            searchLanguage=language;
            searchRegistrySize=BuiltInRegistries.ITEM.size();
        }
        var result = new ArrayList<Item>();
        var byId = ResourceLocation.tryParse(query);
        if (byId != null && BuiltInRegistries.ITEM.containsKey(byId)) {
            result.add(BuiltInRegistries.ITEM.get(byId));
        }
        String lower = query.toLowerCase(Locale.ROOT);
        var partial = new ArrayList<Item>();
        for (var entry : searchIndex) {
            if (result.contains(entry.item())) continue;
            if (entry.name().equalsIgnoreCase(query) || entry.id().equalsIgnoreCase(query)) {
                result.add(0, entry.item());
            } else if (entry.lowerName().contains(lower) || entry.lowerId().contains(lower)) {
                partial.add(entry.item());
            }
        }
        result.addAll(partial);
        return result.size() <= 256 ? List.copyOf(result) : List.copyOf(result.subList(0, 256));
    }

    private void setFieldText(String text) {
        if (itemId == null) return;
        suppressSuggestions = true;
        itemId.setValue(text);
        suppressSuggestions = false;
        suggestionPage = 0;
        suggestions = List.of();
    }

    private void setTargetFromField() {
        if (itemId == null) return;
        String raw = itemId.getValue().trim();
        if (raw.isEmpty()) {
            status = "请输入物品的中文名，或像 ae2:certus_quartz_crystal 这样的物品代码";
            return;
        }
        List<Item> found = searchItems(raw);
        if (found.isEmpty()) {
            status = "没有找到这个物品，请换个名字试试";
            return;
        }
        Item chosen = found.get(0);
        boolean exact = new ItemStack(chosen).getHoverName().getString().equalsIgnoreCase(raw)
                || BuiltInRegistries.ITEM.getKey(chosen).toString().equalsIgnoreCase(raw);
        if (!exact && found.size() > 1) {
            suggestionPage = 0;
            suggestions = found;
            status = "找到多个物品，请从下面的列表里选一个";
            return;
        }
        checkpoint();
        setTarget(AEItemKey.of(chosen), 0, "搜索", "");
        itemId.setFocused(false);
        setFieldText(targetName(target));
    }

    private void setTarget(AEKey key, long amount, String source, String path) {
        expandingJei=false;jeiTreeMode=false;expansionBrowser=null;
        target = key;
        targetAmount = Math.max(0, amount);
        this.source = source == null ? "手动" : source;
        this.path = path == null ? "" : path;
        targetKeyId = key == null ? null : keyId(key);
        selectedKey = targetKeyId;
        selectedNode = null;
        selectedRecipe = null;
        branchEdits.clear();nodeMenu=false;branches.clear(); branchDrafts.clear();  manualBuild=true; draftChoice=null; draft=null;
        materials.clear();
        offsets.clear();
        pending.clear();
        centerPending = true;
        autoCenter = true;
        zoom = 1.0F;
        choiceScroll = 0;
        status = "已设置最终产物；从右侧 JEI 选配方开始创建分支";
        if (itemId != null && !itemId.isFocused()) {
            setFieldText(key instanceof AEItemKey ? targetName(key) : "");
            itemId.setHint(Component.literal(key != null && !(key instanceof AEItemKey)
                    ? "流体目标请从合成树或 JEI 打开" : "中文名，或 ae2:... 物品代码"));
        }        if (key != null) {
            ensureMaterial(key);
            query(key);
        }
        rebuildGraph();
    }

    private void fitCanvas() {
        fitPending=true;
        centerPending = true;
        autoCenter = true;
        zoom = 1.0F;
        panY = 0;
        rebuildGraph();
    }

    private void automaticSelected() {
        var node = activeMaterial();
        if (node == null) {
            status = "先在画布上点一个材料";
            return;
        }
        send("auto", node.material.renderKey, "");
    }

    private void stockSelected() {
        var node = activeMaterial();
        if (node == null) {
            status = "先在画布上点一个材料";
            return;
        }
        if(send("stock", node.material.renderKey, "")!=0)
            status="仅用现有库存：该材料不再自动选配方，缺料即不可合成";
    }

    private MNode activeMaterial() {
        if (selectedNode != null) return selectedNode;
        if (selectedKey != null) {
            for (var node : materialNodes) if (node.material.key.equals(selectedKey)) return node;
        }
        return root;
    }

    private String keyId(AEKey key) {
        if (key == null) return null;
        try {
            return key.toTagGeneric(Minecraft.getInstance().level.registryAccess()).toString();
        } catch (Exception e) {
            return key.toString();
        }
    }

    private Material ensureMaterial(AEKey key) {
        String id = keyId(key);
        return materials.computeIfAbsent(id, ignored -> new Material(key, id,
                key.getDisplayName().getString()));
    }

    private Material ensureMaterial(RouteView.Stack stack) {
        try {
            return ensureMaterial(stack.decode(Minecraft.getInstance().level.registryAccess()));
        } catch (Exception e) {
            throw new IllegalArgumentException("材料数据无法读取：" + stack.name(), e);
        }
    }

    private void query(AEKey key) {
        if (key == null || pending.values().stream().filter(p->p.action.equals("query")).count()>=MAX_QUERY_IN_FLIGHT) return;
        Material material = ensureMaterial(key);
        if (material.reply != null || material.loading || material.error != null) return;
        material.loading = true;
        int sequence = send("query", key, "");
        if (sequence == 0) material.loading = false;
    }

    private int send(String action, AEKey key, String argument) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu.containerId != menuId) {
            status = "终端已关闭，请重新打开路线画布";
            return 0;
        }
        if(action.equals("workbench") && pending.values().stream().anyMatch(p->p.action.equals("workbench"))) {
            // A refresh is already in flight. Drop this one, but never silently: without the
            // log an unresponsive panel looks identical to a server that never answered.
            if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA workbench refresh skipped: request already in flight, menu={}",menuId);
            return 0;
        }
        if (RouteRequestRules.mutation(action) && pending.values().stream().anyMatch(p -> RouteRequestRules.mutation(p.action))) {
            status="上一项操作尚未完成"; return 0;
        }
        if (argument.length()>com.aecraftroute.addon.route.RouteCanvasPayload.MAX_CHARACTERS) { status="配方数据过大，无法编码"; return 0; }
        int sequence = ++nextSequence;
        if(action.equals("preview") || RouteRequestRules.mutation(action)) {
            pending.entrySet().removeIf(e->e.getValue().action.equals("preview") || e.getValue().action.equals("apply"));
            latestPlanSequence=sequence;
        }
        pending.put(sequence, new Pending(key, action,ticks,previewSignature()));
        if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA request: menu={}, sequence={}, action={}, target={}, signature={}",menuId,sequence,action,key,previewSignature());
        PacketDistributor.sendToServer(new RouteNetwork.Request(menuId, sequence, action, key, argument));
        if (RouteRequestRules.mutation(action)) status = "等待服务器确认操作…";
        return sequence;
    }

    private com.aecraftroute.addon.route.RoutePlanningProgress planningProgress;
    public void accept(RouteNetwork.Response response) {
        if (response.menu() != menuId) {
            // Belongs to another menu generation; log it so a mismatched reply is never invisible.
            if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA response for foreign menu: menu={}, response={}, sequence={}",menuId,response.menu(),response.sequence());
            return;
        }
        Pending request = pending.get(response.sequence());
        if(response.sequence()==canvasChunkSequence && !canvasChunks.isEmpty())canvasChunks.clear();
        if (request == null) {
            if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA response without pending request: menu={}, sequence={}",menuId,response.sequence());
            return;
        }
        if((request.action.equals("preview") || request.action.equals("apply"))
                && !RouteRequestRules.currentPlanResponse(response.sequence(),latestPlanSequence,request.signature,previewSignature())) {
            pending.remove(response.sequence());
            // The target/amount moved on while this was in flight, so the verdict is stale.
            // Release the dedupe gate: otherwise this signature is never asked again and the
            // authoritatively verified panel stays empty forever.
            lastPreviewSignature=null;
            if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA stale plan response: menu={}, sequence={}, action={}",menuId,response.sequence(),request.action);
            return;
        }
        try {
            var update=com.google.gson.JsonParser.parseString(response.json()).getAsJsonObject();
            if(update.has("planningProgress")) {
                var progress=RouteNetwork.GSON.fromJson(update.get("planningProgress"),com.aecraftroute.addon.route.RoutePlanningProgress.class);
                var incoming=new com.aecraftroute.addon.route.RoutePlanningProgress(response.sequence(),progress.revision(),progress.phase(),progress.elapsedMillis(),false);
                if(incoming.supersedes(planningProgress)) {
                    planningProgress=incoming;
                    String phase=switch(progress.phase()) {
                        case "capture" -> "抓取网络";
                        case "worker queue" -> "等待计算";
                        case "worker computation" -> "搜索与认证";
                        case "validation" -> "校验当前库存";
                        default -> progress.phase();
                    };
                    status="规划中 · "+phase+" · "+progress.elapsedMillis()+" ms";
                }
                return; // Intermediate updates retain the pending request.
            }
        } catch(RuntimeException ignored) { /* Final parser reports malformed data. */ }
        pending.remove(response.sequence());
        RouteView.Reply reply;
        try {
            var json=com.google.gson.JsonParser.parseString(response.json()).getAsJsonObject();
            if(json.has("workbench") && json.get("workbench").getAsBoolean()) {
                if(response.sequence()<lastWorkbenchSequence)return;
                lastWorkbenchSequence=response.sequence();
                var state=RouteNetwork.GSON.fromJson(json,RouteWorkbench.State.class);workbench.accept(state);
                if(state.message()!=null && !state.message().isEmpty())status=state.message();
                // This branch returns before the generic response log below, so a handled
                // workbench reply used to leave no trace at all and looked exactly like a
                // dropped packet. Log it here.
                if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA workbench response: menu={}, sequence={}, revision={}, cache={}, providers={}",menuId,response.sequence(),state.revision(),state.cache()==null?0:state.cache().size(),state.providers()==null?0:state.providers().size());
                return;
            }
            reply = RouteNetwork.GSON.fromJson(response.json(), RouteView.Reply.class);
        } catch (Exception e) {
            status = "服务器返回的数据无法读取";
            if(request.key!=null && request.action.equals("query")) {
                var material=materials.get(keyId(request.key));
                if(material!=null){material.loading=false;material.error=status;}
                rebuildGraph();
            }
            return;
        }
        if (reply == null) {
            if(request.key!=null && request.action.equals("query")) {
                var material=materials.get(keyId(request.key));
                if(material!=null){material.loading=false;material.error="服务器返回空结果";}
                rebuildGraph();
            }
            return;
        }
        appliedPathChoices.clear();appliedPathDrafts.clear();
        if(reply.canvas()!=null && Objects.equals(reply.canvas().target(),keyId(target))
                && reply.canvas().amount()==Math.max(1,targetAmount) && reply.canvas().paths()!=null)
            for(var p:reply.canvas().paths()) {
                appliedPathChoices.put(p.path(),p.choiceId());
                if(p.draft()!=null)appliedPathDrafts.put(p.path(),p.draft());
            }
        if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA response: menu={}, sequence={}, action={}, verdict={}, message={}",menuId,response.sequence(),request.action,reply.plan()==null?null:reply.plan().verdict(),reply.message());

        if(request.action.equals("begin")) {
            if(reply.message()!=null && reply.message().startsWith("操作失败")) {status=reply.message();return;}
            if(fromCrafting && reply.requestTarget()!=null) {
                try {
                    var authoritative=RouteView.decodeKey(reply.requestTarget().key(),Minecraft.getInstance().level.registryAccess());
                    if(authoritative==null || reply.requestTarget().amount()<=0)throw new IllegalArgumentException("合成目标无效");
                    originalTarget=authoritative;originalAmount=reply.requestTarget().amount();
                    if(target==null) {
                        target=authoritative;targetAmount=originalAmount;targetKeyId=keyId(target);selectedKey=targetKeyId;
                        source="合成确认（服务端目标）";manualBuild=false;centerPending=true;autoCenter=true;
                        lastPreviewSignature=null;setFieldText(targetName(target));ensureMaterial(target);query(target);
                        status="已读取服务端合成目标，正在加载网络样板…";rebuildGraph();
                        if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA authoritative begin target: menu={}, target={}, amount={}",menuId,target,targetAmount);
                    }
                } catch(Exception invalid) {status="读取服务端合成目标失败："+invalid.getMessage();}
            }
            if(target!=null) beginRetry.answered();
            return;
        }
        if(request.action.equals("restore")) {
            if(reply.message()!=null && reply.message().startsWith("操作失败")) {status=reply.message();return;}
            checkpoint(); target=originalTarget;targetAmount=originalAmount;targetKeyId=keyId(target);
            manualBuild=false;branchEdits.clear();nodeMenu=false;branches.clear();branchDrafts.clear();offsets.clear();draftChoice=null;draft=null;
            centerPending=true;autoCenter=true;setFieldText(targetName(target));
        }
        if (request.action.equals("clear") || request.action.equals("clearcanvas") || request.action.equals("restore")) {
            if(reply.message()!=null && reply.message().startsWith("操作失败")) {status=reply.message();return;}
            lastPreviewSignature=null;
            acceptPlan(null);
            materials.clear();
            pending.clear();
            root = null;
            selectedNode = null;
            selectedRecipe = null;
                if (target != null) {
                ensureMaterial(target);
                query(target);
            }
            status = reply.message();
            rebuildGraph();
            return;
        }
        if ("preview".equals(request.action)) {
            acceptPlan(reply.plan());
            // Remember the answered signature here, not at send time: a lost or stale reply
            // must be retryable, otherwise the authoritative panel stays blank for good.
            lastPreviewSignature = request.signature;
            String summary = planSummary();
            status = summary != null ? summary : reply.message();
            rebuildGraph();
            return;
        }
        if ("apply".equals(request.action)) {
            acceptPlan(reply.plan());
            // The applied verdict is what a later preview would return; do not refetch it.
            lastPreviewSignature = target == null ? null : keyId(target) + ":" + Math.max(1, targetAmount);
            status = reply.message() != null && !reply.message().isBlank() ? reply.message() : planSummary();
            rebuildGraph();
            return;
        }
        if (request.action.equals("replan") || request.action.equals("encode") || Set.of("workbench","takecache","upload").contains(request.action)) {
            status = reply.message();
            return;
        }

        Material material = request.key == null ? null : materials.get(keyId(request.key));
        if (reply.message() != null && reply.message().startsWith("操作失败")) {
            if (material != null) {
                material.loading = false;
                material.error = reply.message();
            }
            status = reply.message();
            rebuildGraph();
            return;
        }
        if (material != null) {
            material.reply = reply;
            material.loading = false;
            material.error = null;
        }
        if (request.action.equals("query")) {
            if (status.startsWith("读取") || status.startsWith("正在读取")) status = "点击材料，在右侧选配方逐层展开";
        } else {
            status = request.action.equals("stock")?"仅用现有库存：该材料不再自动选配方，缺料即不可合成":reply.message() == null ? "就绪" : reply.message();
        }
        if ("lock".equals(request.action) || "ban".equals(request.action)
                || "auto".equals(request.action) || "stock".equals(request.action)) {
            lastPreviewSignature = null; // rules changed: the old verdict is stale
        }
        rebuildGraph();
    }

    private String previewSignature() {return target==null?null:keyId(target)+":"+Math.max(1,targetAmount); }
    /** Ask the server what this request really costs; replaces the old single-pass division. */
    private void maybeRequestPreview() {
        if (target == null) return;
        String signature = previewSignature();
        if (signature.equals(lastPreviewSignature)) return;
        // Dedupe on requests that are actually in flight, not on requests already answered.
        // Keying the gate on "sent" made a single lost reply permanent: the same target and
        // amount was never asked again, so the authoritative panel stayed blank until the
        // player changed one of them. The gate is now released by the reply, by a stale
        // reply, and by expiry (see accept and tick).
        if (pending.values().stream().anyMatch(p -> ("preview".equals(p.action) || "apply".equals(p.action))
                && signature.equals(p.signature))) return;
        int sequence = send("preview", target, "{\"amount\":" + Math.max(1, targetAmount) + "}");
        if (sequence != 0) acceptPlan(null);
    }

    /**
     * Submit every recipe chosen on the canvas at once.
     *
     * <p>Nothing is applied silently: the server reports which materials it locked, which
     * picks are stale, and which material needs two different recipes (a per-material lock
     * cannot express that). The reply carries the authoritative plan for the new rules.</p>
     */
    private void applyCanvas() {
        if(expandingJei) {status="JEI 配方仍在展开；请等待完成";return;}
        if (!canModify) { status = "请从自动合成确认界面进入修改模式"; return; }
        if (target == null) { status = "先设置最终产物，再整幅应用"; return; }
        var nodes = new ArrayList<com.aecraftroute.addon.route.RouteApply.Node>();
        int skipped = 0;
        for (var node : materialNodes) {
            var choice = node.preview == null ? null : node.preview.choice();
            if (choice == null || choice.id().startsWith("plan:") || node.material.key == null) { skipped++; continue; }
            nodes.add(new com.aecraftroute.addon.route.RouteApply.Node(node.material.key,choice.id(),node.material.name,node.path,
                    branchDrafts.getOrDefault(node.path,appliedPathDrafts.get(node.path))));
        }
        if (nodes.isEmpty()) { status = "画布上还没有可应用的配方；先展开材料并选择样板"; return; }
        String argument;
        try {argument=com.aecraftroute.addon.route.RouteCanvasPayload.encode(targetAmount,nodes);}
        catch(IllegalArgumentException tooLarge){status=tooLarge.getMessage();return;}
        status = "正在证明强制画布：" + nodes.size() + " 个路径分支（每分支至少执行画布批次）"
                + (skipped > 0 ? "（" + skipped + " 个节点没有可应用样板）" : "")
               ;
        if(argument.length()<=RouteCanvasPayload.MAX_CHARACTERS)send("apply",target,argument);
        else sendLargeCanvas(argument);
    }

    private void sendLargeCanvas(String argument) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.player.containerMenu.containerId!=menuId) {status="终端已关闭，未发送画布";return;}
        if(pending.values().stream().anyMatch(p->RouteRequestRules.mutation(p.action))) {status="上一项操作尚未完成";return;}
        int sequence=++nextSequence;latestPlanSequence=sequence;
        pending.entrySet().removeIf(e->e.getValue().action.equals("preview") || e.getValue().action.equals("apply"));
        pending.put(sequence,new Pending(target,"apply",ticks,previewSignature()));
        var chunks=RouteCanvasChunks.split(argument);
        canvasChunks.clear();canvasChunkSequence=sequence;
        for(int i=0;i<chunks.size();i++)canvasChunks.add(new RouteNetwork.Request(
                menuId,sequence,"apply_chunk",target,i+"|"+chunks.size()+"|"+chunks.get(i)));
        status="正在分 "+chunks.size()+" 片发送完整画布";
    }
    /** Bound network work per client tick. Never send a stale partial edit. */
    private void pumpCanvasChunks() {
        if(canvasChunks.isEmpty())return;
        var request=pending.get(canvasChunkSequence);
        var mc=Minecraft.getInstance();
        if(request==null || !Objects.equals(request.signature,previewSignature()) || mc.player==null
                || mc.player.containerMenu.containerId!=menuId) {
            canvasChunks.clear();pending.remove(canvasChunkSequence);status="画布或终端已变化，停止发送；未应用";return;
        }
        pending.put(canvasChunkSequence,new Pending(request.key,request.action,ticks,request.signature));
        for(int i=0;i<8 && !canvasChunks.isEmpty();i++)PacketDistributor.sendToServer(canvasChunks.removeFirst());
        if(canvasChunks.isEmpty())status="完整画布已发送，等待服务器证明";
    }

    private void acceptPlan(RoutePlanView value) {
        plan=value;startupByKey=Map.of();
        if(value!=null && value.proven())try {
            startupByKey=RouteStartupIndex.of(value.startup(),stack->stack.decode(Minecraft.getInstance().level.registryAccess()));
        } catch(IllegalArgumentException invalid) {LOGGER.warn("Cannot decode route startup material",invalid);}
    }
    /** Startup capital this plan proves for one AE key, not inventory coverage. */
    private String provenStartup(AEKey key) {
        Long amount=startupByKey.get(key);
        return plan!=null && plan.proven() && amount!=null?RouteGraphMath.format(amount):null;
    }

    private String planSummary() {
        if (plan == null) return null;
        String head = switch (plan.verdict()) {
            case RoutePlanView.FEASIBLE -> "权威：可合成";
            case RoutePlanView.UNREACHABLE -> "权威：达不到目标";
            case RoutePlanView.LIMIT -> "权威：预算内未判定";
            case RoutePlanView.UNSUPPORTED -> "权威：无法精确建模";
            default -> "权威：出错";
        };
        var text = new StringBuilder(head);
        if (!plan.startup().isEmpty()) {
            text.append(" · 起手 ");
            int shown = 0;
            for (var stack : plan.startup()) {
                if (shown++ >= 4) { text.append(" 等"); break; }
                text.append(RouteGraphMath.format(stack.amount())).append(" ").append(stack.name()).append(" ");
            }
        }
        if (!plan.steps().isEmpty()) {
            var first = plan.steps().get(0);
            text.append("· ").append(first.count()).append(" 批 ").append(first.name());
        }
        if (plan.bytes() > 0) text.append(" · ").append(plan.bytes()).append("B");
        return text.toString();
    }

    private RouteAnchor captureAnchor() {
        if(centerPending || fitPending || root==null)return null;
        MNode node=inside(canvasMouseX,canvasMouseY,canvasLeft,canvasTop+18,canvasWidth(),canvasBottom-canvasTop-18)
                ?materialAt(canvasMouseX,canvasMouseY):null;
        if(node==null)node=selectedNode==null?root:selectedNode;
        return new RouteAnchor(node.path,node.material.key,node.x+node.w/2,node.y+node.h/2);
    }
    private void restoreAnchor(RouteAnchor anchor) {
        if(anchor==null)return;
        var node=findByPath(anchor.path());if(node==null)return;
        var pan=anchor.restore(node.material.key,node.x+node.w/2,node.y+node.h/2,panX,panY);
        panX=pan.x();panY=pan.y();
    }
    private void rebuildGraph() {
        var anchor=captureAnchor();
        materialNodes.clear();
        recipeNodes.clear();
        root = null;
        if (target != null) {
            Material material = ensureMaterial(target);
            root = buildNode(material, targetAmount > 0 ? targetAmount : 1, "root", 0,
                    new LinkedHashSet<>());
            if (root != null) {
                measure(root);
                place(root, 0, 0);
            }
        }
        if (centerPending) {
            panY = 0;
            centerPending = false;
        }
        if (root != null && autoCenter && anchor==null) panX = Math.round(canvasWidth() / zoom / 2 - root.subtreeW / 2F);
        if (root != null) {applyOffsets(root);constrainGraph();}
        restoreAnchor(anchor);
        if(fitPending && root!=null) {
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
            for(var node:materialNodes) {if(node.inlineResource)continue;minX=Math.min(minX,node.x);minY=Math.min(minY,node.y);maxX=Math.max(maxX,node.x+node.w);maxY=Math.max(maxY,node.y+node.h);}
            for(var node:recipeNodes) {minX=Math.min(minX,node.x);minY=Math.min(minY,node.y);maxX=Math.max(maxX,node.x+node.w);maxY=Math.max(maxY,node.y+node.h);}
            int availableH=canvasBottom-canvasTop-18;
            var fit=RouteLayout.fit(minX,minY,maxX,maxY,canvasWidth(),availableH);
            zoom=fit.zoom();panX=fit.panX();panY=fit.panY();
            autoCenter=false;fitPending=false;
        }

        if (selectedNode != null) {
            MNode found = findByPath(selectedNode.path);
            selectedNode = found == null ? root : found;
        } else {
            selectedNode = root;
        }
        if (selectedNode != null) selectedKey = selectedNode.material.key;
        if (selectedRecipe != null) {
            RNode found = null;
            for (var recipe : recipeNodes) {
                if (recipe.parent.path.equals(selectedRecipe.parent.path)
                        && recipe.choice.id().equals(selectedRecipe.choice.id())) {
                    found = recipe;
                    break;
                }
            }
            selectedRecipe = found;
        }
        requestMissing();
        maybeRequestPreview();
    }

    private MNode findByPath(String path) {
        for (var node : materialNodes) if (node.path.equals(path)) return node;
        return null;
    }

    private MNode buildNode(Material material, long demand, String path, int depth,
                            Set<String> ancestors) {
        if (depth > 0 && branchEdits.materialHidden(material.key)) {
            LOGGER.info("Skipping hidden material at {}: {}",path,material.key);
            return null;
        }
        boolean cyc = ancestors.contains(material.key);
        MNode node = new MNode(path, material, demand, depth);
        materialNodes.add(node);
        var custom=branches.get(path);
        var planned=originalChoices.get(material.key);
        if(planned!=null && material.reply!=null) {
            for(var candidate:material.reply.choices()) {
                if(sameAmounts(candidate,planned)) {
                    planned=candidate;break;
                }
            }
        }
        node.preview = custom!=null ? new RouteGraphMath.Preview(custom,false,custom.id().startsWith("jei:"),"手动创建")
                : manualBuild ? new RouteGraphMath.Preview(null,false,false,"选择右侧配方创建分支")
                : planned!=null ? new RouteGraphMath.Preview(planned,false,false,"原始合成计划")
                : !originalChoices.isEmpty() ? new RouteGraphMath.Preview(null,false,false,"系统计划使用库存或原材料")
                : RouteGraphMath.preview(material.reply, draftFor(material.key));
        if(!jeiTreeMode && custom==null && appliedPathChoices.containsKey(path)) {
            var id=appliedPathChoices.get(path);RouteView.Choice matched=null;
            var appliedDraft=appliedPathDrafts.get(path);
            if(appliedDraft!=null && id.equals("jei:"+appliedDraft.recipeId()))matched=draftChoice(appliedDraft);
            if(matched==null && material.reply!=null) for(var candidate:material.reply.choices())if(id.equals(candidate.id())){matched=candidate;break;}
            node.preview=new RouteGraphMath.Preview(matched,true,appliedDraft!=null,
                    matched!=null?"已应用的服务端验证路径":material.reply==null?"正在读取已应用路径样板…":"已应用的强制路径（缺失时不改用别的配方）");
        }
        node.cycle = ancestors.contains(material.key);
        if(!jeiTreeMode && material.reply!=null && material.reply.stockOnly())node.preview=RouteGraphMath.preview(material.reply,null);
        if (node.cycle) {
            node.preview = new RouteGraphMath.Preview(null, false, false, "回料引用：此处连接上层材料；可行性由服务端库存与执行顺序验证");
            return node;
        }
        if (node.preview.choice() == null) return node;

        RouteView.Choice choice = node.preview.choice();
        long output = RouteGraphMath.outputAmount(choice, material.key);
        if (output <= 0) {
            node.preview = new RouteGraphMath.Preview(choice, node.preview.locked(),
                    node.preview.draft(), "这张配方不产出这个材料，不能放在这里");
            return node;
        }
        node.recipe = new RNode(node, choice, node.preview.draft());
        recipeNodes.add(node.recipe);
        if(!branchEdits.expanded(path))return node;

        long runs;
        try {
            runs = RouteGraphMath.runs(demand, output);
        } catch (Exception e) {
            node.recipeError = e.getMessage();
            return node;
        }
        ancestors.add(material.key);
        try {
            var grouped=RouteGraphMath.groupedInputs(choice.inputs());
            for (int i = 0; i < grouped.size(); i++) {
                RouteView.Stack stack = grouped.get(i);
                Material childMaterial = ensureMaterial(stack);
                long childDemand;
                try {
                    childDemand = RouteGraphMath.inputNeed(stack.amount(), runs);
                } catch (ArithmeticException e) {
                    childDemand = Long.MAX_VALUE;
                    node.recipeError = "数量过大，画布仅显示上限";
                }
                String childPath=RouteGraphMath.childPath(path,i);
                if(branchEdits.childHidden(path,i)) continue;
                if(!branchEdits.shown(path,childPath,childMaterial.key))continue;
                MNode child = buildNode(childMaterial, childDemand,childPath,depth + 1, ancestors);
                if (child != null) {
                    child.inlineResource=RouteEmcPresentation.inline(RouteEmcPresentation.isCurrency(child.material.renderKey),child.recipe!=null,child.cycle);
                    node.children.add(child);
                }
            }
        } catch (Exception e) {
            node.recipeError = e.getMessage();
        } finally {
            ancestors.remove(material.key);
        }
        return node;
    }

    private RouteView.Choice draftFor(String key) {
        if (draftChoice == null || targetKeyId == null || !key.equals(targetKeyId)) return null;
        return draftChoice;
    }

    private int measure(MNode node) {
        node.w = node.depth == 0 ? 100 : 86;
        node.h = RouteQuantityField.NODE_HEIGHT;
        if (node.recipe != null) {
            node.recipe.w = 18;
            node.recipe.h = 18;
            int row = 0;
            for (var child : node.children) {
                if(child.inlineResource)continue;
                int width = measure(child);
                row += width + (row == 0 ? 0 : 14);
            }
            node.subtreeW = Math.max(Math.max(node.w, node.recipe.w), row);
        } else {
            node.subtreeW = node.w;
        }
        return node.subtreeW;
    }

    private void place(MNode node, int left, int top) {
        node.autoX = left + (node.subtreeW - node.w) / 2;
        node.autoY = top;
        node.x = node.autoX;
        node.y = node.autoY;
        if (node.recipe == null) return;

        RNode recipe = node.recipe;
        var port=RouteMaterialPort.of(node.autoX,node.autoY,node.w);
        recipe.autoX = port.x();recipe.autoY = port.y();
        recipe.x = recipe.autoX;
        recipe.y = recipe.autoY;

        int total = 0;
        for (var child : node.children) if(!child.inlineResource)total += child.subtreeW + (total == 0 ? 0 : 14);
        int childLeft = left + (node.subtreeW - total) / 2;
        int childTop = top+node.h+RouteTreeGeometry.Y_GAP;
        for (var child : node.children) {
            if(child.inlineResource)continue;
            place(child, childLeft, childTop);
            childLeft += child.subtreeW + 14;
        }
    }

    private void applyOffsets(MNode node) {applyOffsets(node,0,0);}
    private void applyOffsets(MNode node,int parentDx,int parentDy) {
        var offset=offsets.get("m:"+node.path);
        int dx=parentDx+(offset==null?0:offset[0]),dy=parentDy+(offset==null?0:offset[1]);
        node.x=node.autoX+dx;node.y=node.autoY+dy;
        attachRecipe(node);
        for(var child:node.children)applyOffsets(child,dx,dy);
    }
    private void attachRecipe(MNode node) {
        if(node.recipe!=null) {
            var port=RouteMaterialPort.of(node.x,node.y,node.w);
            node.recipe.x=port.x();node.recipe.y=port.y();node.recipe.w=18;node.recipe.h=18;
        }
    }
    private List<RouteTreeGeometry.Box> graphGeometry() {
        return materialNodes.stream().filter(node->!node.inlineResource).map(node->
                new RouteTreeGeometry.Box(node.path,node.depth,node.x,node.y,node.w,node.h)).toList();
    }
    private void applyGeometry(List<RouteTreeGeometry.Box> boxes) {
        for(var box:boxes) {var node=findByPath(box.path());if(node!=null) {node.x=box.x();node.y=box.y();attachRecipe(node);}}
    }
    private void constrainGraph() {applyGeometry(RouteTreeGeometry.constrain(graphGeometry()));}
    private void saveGeometryOffsets() {
        for(var node:materialNodes) {
            if(node.inlineResource)continue;
            var parentPath=RouteGraphMath.parentPath(node.path);
            var parent=parentPath==null?null:findByPath(parentPath);
            var delta=RouteTreeGeometry.relative(node.x,node.y,node.autoX,node.autoY,
                    parent==null?0:parent.x-parent.autoX,parent==null?0:parent.y-parent.autoY);
            if(delta[0]==0 && delta[1]==0)offsets.remove("m:"+node.path);else offsets.put("m:"+node.path,delta);
        }
    }

    private void requestMissing() {
        if (jeiTreeMode) return;
        for (var node : new ArrayList<>(materialNodes)) {
            if (node.material.reply == null && !node.material.loading && node.material.error == null) {
                query(node.material.renderKey);
                if (pending.values().stream().filter(p->p.action.equals("query")).count()>=MAX_QUERY_IN_FLIGHT) break;
            }
        }
    }

    private int screenX(MNode node) { return canvasLeft + Math.round((panX + node.x) * zoom); }
    private int screenY(MNode node) { return canvasTop + 18 + Math.round((panY + node.y) * zoom); }
    private double contentX(double mouseX) { return (mouseX - canvasLeft) / zoom - panX; }
    private double contentY(double mouseY) { return (mouseY - canvasTop - 18) / zoom - panY; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        // Keep the NeoForge BackgroundRendered hook alive: JEI draws its own list background there.
        super.renderBackground(g,mx,my,dt);
        appeng.client.gui.style.BackgroundGenerator.draw(windowBounds().width(),windowBounds().height(),g,windowBounds().left(),windowBounds().top());
        if (!usable()) return;
        g.fill(canvasLeft-2,canvasTop-2,canvasRight+2,canvasBottom+2,CANVAS_EDGE);
        g.fill(canvasLeft,canvasTop,canvasRight,canvasBottom,CANVAS_BG);
        for(int gx=canvasLeft+8;gx<canvasRight;gx+=16)
            for(int gy=canvasTop+24;gy<canvasBottom;gy+=16)
                g.fill(gx,gy,gx+1,gy+1,CANVAS_GRID);
        if(!narrow() || narrowInspector)appeng.client.gui.style.BackgroundGenerator.draw(panelRight-panelLeft+4,canvasBottom-canvasTop+4,g,panelLeft-2,canvasTop-2);
        if(!narrow() || narrowInspector)frame(g,panelLeft-2,canvasTop-2,panelW+4,canvasBottom-canvasTop+4,CANVAS_EDGE);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        // super.render calls renderBackground (which fires BackgroundRendered for JEI) and
        // renders the vanilla widgets. JEI's ingredient list is drawn later in Render.Post,
        // i.e. above this content, so the right-hand strip stays a real JEI panel.
        super.render(g,mx,my,dt);
        if (!usable()) {
            text(g,"终端窗口过小",windowBounds().left()+14,windowBounds().top()+28,windowBounds().width()-28,GOLD);
            text(g,"请调大 AE 终端尺寸后重开画布",windowBounds().left()+14,windowBounds().top()+42,windowBounds().width()-28,TEXT);
            return;
        }
        drawHeader(g);
        if(!narrow() || !narrowInspector)drawCanvas(g,mx,my);
        if(!narrow() || narrowInspector)drawInspector(g,mx,my);else {choiceHits.clear();actionHits.clear();}
        g.flush();g.pose().pushPose();g.pose().translate(0,0,600);
        if(workbenchVisible())workbench.render(g,mx,my);
        g.flush();g.pose().popPose();
        drawFooter(g);
        if(browser!=null && browser.draggingIngredient()) {wireX=mx;wireY=my;}
        drawWire(g);
        drawSuggestions(g, mx, my);
        drawNodeTooltip(g,mx,my);
        if(workbenchVisible())workbench.renderDrag(g);
        drawNodeMenu(g,mx,my);
        if(browser!=null) {
            browser.renderDrag(g);
            if(recipeHover.visible())browser.clearMachineOverlay();else browser.renderMachineOverlay(g,mx,my);
        }
    }

    private void drawHeader(GuiGraphics g) {
        if(narrow())return;
        int left=windowBounds().left(),top=windowBounds().top();
        text(g,compact()?"合成树":"合成树画布",left+(compact()?8:10),top+7,compact()?44:88,TEXT);
        if(compact())return;
        String exit = "Esc 返回";
        text(g, exit, contentRight() - 10 - font.width(exit), top+7, font.width(exit) + 2, FAINT);
        if (target == null) {
            text(g, "未设置生产目标", left+12, top+26, windowBounds().width()-40, GOLD);
            text(g, "输入物品的中文名或物品代码，也可以从合成树节点按 F8，或把 JEI 里的物品拖进来", left+12, top+37,
                    Math.max(1, (itemId == null ? contentRight() : itemId.getX()) - left - 52), MUTED);
            if (itemId != null) {
                g.fill(itemId.getX() - 20, top+23, itemId.getX() - 4, top+39, 0xFFB8B8B8);
                frame(g, itemId.getX() - 20, top+23, 16, 16, BORDER);
                text(g, "?", itemId.getX() - 15, top+27, 12, MUTED);
            }
            return;
        }
        icon(g, target, left+14, top+26);
        int textX = left+34;
        int max = Math.max(1, (itemId == null ? contentRight() - 120 : itemId.getX()) - left - 62);
        String goal = "目标  " + target.getDisplayName().getString();
        text(g, goal, textX, top+24, max, GOLD);
        String amount = targetAmount > 0 ? RouteGraphMath.format(targetAmount) : "未指定";
        String amountLabel = source != null && source.startsWith("JEI") ? "单次产出" : "需求";
        String line = amountLabel + " " + amount + "  ·  来源 " + source;
        if (!path.isBlank()) line += "  ·  " + path;
        text(g, line, textX, top+35, max, MUTED);
        if (itemId != null && target instanceof AEItemKey) {
            icon(g, target, itemId.getX() - 20, top+23);
        }
    }

    private void drawCanvas(GuiGraphics g, int mx, int my) {
        canvasMouseX=mx;canvasMouseY=my;
        int right = canvasRight - 4;
        text(g, "画布", canvasLeft + 4, canvasTop + 3, 40, TEXT);
        boolean legacy=com.aecraftroute.addon.config.CraftRouteConfig.scrollEditsQuantity();
        String legend=(legacy?"Ctrl+滚轮缩放 · 滚轮数量 ":"滚轮缩放 · Shift 平移 · Alt 数量 ")+Math.round(zoom*100)+"%";
        int legendWidth=Math.max(1,Math.min(font.width(legend),right-canvasLeft-44));
        text(g,legend,right-legendWidth,canvasTop+3,legendWidth,FAINT);
        if (root == null) {
            text(g, "还没有可显示的生产目标", canvasLeft + 12, canvasTop + 52,
                    canvasWidth() - 24, MUTED);
            text(g, "从合成树节点按 F8，或在右上角输入中文名或物品代码。", canvasLeft + 12,
                    canvasTop + 66, canvasWidth() - 24, FAINT);
            return;
        }

        g.enableScissor(canvasLeft, canvasTop + 16, canvasRight, canvasBottom);
        g.pose().pushPose();
        g.pose().translate(canvasLeft, canvasTop + 18, 0);
        g.pose().scale(zoom, zoom, 1.0F);
        g.pose().translate(panX, panY, 0);
        drawEdges(g, root);
        for (var node : materialNodes) if(!node.inlineResource)drawMaterial(g, node, mx, my);
        g.pose().popPose();
        g.disableScissor();
    }

    private void drawEdges(GuiGraphics g,MNode node) {
        for(var child:node.children)if(!child.inlineResource) {
            drawEdge(g,node.x+node.w/2,node.y+node.h,child.x+child.w/2,child.y,
                    edgeColor(node),node.preview.locked()||node.preview.draft());
            drawEdges(g,child);
        }
    }

    private int edgeColor(MNode node) {
        if (node.preview.draft()) return PURPLE;
        if (node.preview.locked()) return ACCENT;
        if (node.material.reply != null && node.material.reply.stockOnly()) return RED;
        return 0xFF666666;
    }

    private void drawEdge(GuiGraphics g, int x1, int y1, int x2, int y2, int color, boolean solid) {
        int mid = y1 + Math.max(6, (y2 - y1) / 2);
        drawV(g, x1, y1, mid, color, solid);
        drawH(g, x1, x2, mid, color, solid);
        drawV(g, x2, mid, y2 - 4, color, solid);
        g.fill(x2 - 3, y2 - 4, x2 + 3, y2, color);
        g.fill(x2 - 2, y2 - 4, x2 + 2, y2 - 2, color);
    }

    private void drawV(GuiGraphics g, int x, int y1, int y2, int color, boolean solid) {
        if (y2 <= y1) return;
        if (solid) {
            g.fill(x, y1, x + 1, y2, color);
        } else {
            for (int y = y1; y < y2; y += 6) g.fill(x, y, x + 1, Math.min(y2, y + 3), color);
        }
    }

    private void drawH(GuiGraphics g, int x1, int x2, int y, int color, boolean solid) {
        int a = Math.min(x1, x2), b = Math.max(x1, x2);
        if (b <= a) return;
        if (solid) {
            g.fill(a, y, b, y + 1, color);
        } else {
            for (int x = a; x < b; x += 6) g.fill(x, y, Math.min(b, x + 3), y + 1, color);
        }
    }

    private void drawMaterial(GuiGraphics g, MNode node, int mx, int my) {
        int sx=screenX(node), sy=screenY(node);
        int sw=Math.max(1,Math.round(node.w*zoom)), sh=Math.max(1,Math.round(node.h*zoom));
        if(sx+sw<canvasLeft || sx>canvasRight || sy+sh<canvasTop || sy>canvasBottom)return;
        int x=node.x,y=node.y;
        boolean selected=selectedNode==node && selectedRecipe==null;
        boolean hover=inside(mx,my,sx,sy,sw,sh);

        // A server proof describes the applied route, not any pending local branch. Keep
        // the whole draft conservative until it is applied, including untouched siblings.
        var visual=RouteNodeVisual.of(plan!=null && plan.proven(),node.material.reply!=null && node.material.reply.stockOnly(),!branches.isEmpty());
        int border=node.cycle?ACCENT:selected?ACCENT:visual.frame();
        g.fill(x,y,x+node.w,y+node.h,selected?0xFFC6C6D4:hover?0xFFD8D9E0:0xFFCBCCD4);
        bevel(g,x,y,node.w,node.h); frame(g,x,y,node.w,node.h,border);
        int slotX=x+(node.w-28)/2,slotY=y+4;
        g.pose().pushPose();g.pose().translate(slotX,slotY,0);g.pose().scale(1.5F,1.5F,1);
        appeng.client.gui.Icon.SLOT_BACKGROUND.getBlitter().dest(0,0).blit(g);
        icon(g,node.material.renderKey,1,1);
        g.pose().popPose();
        drawQuantityField(g,node,mx,my,visual);
        String name=font.plainSubstrByWidth(node.material.name,node.w-8);
        if(!name.equals(node.material.name))name=font.plainSubstrByWidth(node.material.name,node.w-14)+"…";
        text(g,name,x+(node.w-font.width(name))/2,y+RouteQuantityField.NAME_Y,node.w-6,TEXT);
        drawNodeStatus(g,node,mx,my,border);
        drawRecipePort(g,node,mx,my);
    }

    private void drawNodeStatus(GuiGraphics g,MNode node,int mx,int my,int border) {
        var row=RouteNodeStatus.of(node.x,node.y,node.w);
        boolean hover=row.contains(contentX(mx),contentY(my));
        if(hover && !nodeMenu)g.fill(row.x(),row.y(),row.x()+row.width(),row.y()+row.height(),0xFFC6C6D4);
        String label=RouteNodeStatus.label(node==root,node.cycle,node.recipe!=null,branchEdits.expanded(node.path),emcCostLabel(node)!=null);
        label=font.plainSubstrByWidth(label,row.width()-2);
        text(g,label,row.x()+(row.width()-font.width(label))/2,node.y+RouteQuantityField.STATE_Y,row.width(),node.cycle?ACCENT:MUTED);
        g.fill(node.x+node.w/2-1,node.y+node.h-1,node.x+node.w/2+1,node.y+node.h-1+(branchEdits.expanded(node.path)?7:2),border);
    }
    private MNode statusAt(double mx,double my) {
        if(suggestionsVisible() && inside(mx,my,suggestionX,suggestionY,suggestionW,suggestionH))return null;
        var node=materialAt(mx,my);return node!=null && RouteNodeStatus.of(node.x,node.y,node.w).contains(contentX(mx),contentY(my))?node:null;
    }
    private void drawStatusTooltip(GuiGraphics g,MNode node,int mx,int my) {
        String proof=plan==null?null:plan.proven()?"网络计划已证明；不表示本地编辑已应用":"网络计划："+plan.message();
        var details=new RouteNodeStatus.Details(node.material.name,node.demand,node.cycle,node.recipe!=null,branchEdits.expanded(node.path),
                (int)node.children.stream().filter(child->!child.inlineResource).count(),proof,provenStartup(node.material.renderKey),
                !branches.isEmpty(),emcCostLabel(node),node.recipeError);
        g.renderComponentTooltip(font,RouteNodeStatus.details(details).stream().map(Component::literal).map(c->(Component)c).toList(),mx,my);
    }

    private void drawQuantityField(GuiGraphics g,MNode node,int mx,int my,RouteNodeVisual visual) {
        var field=RouteQuantityField.of(node.x,node.y,node.w);boolean editing=node.path.equals(quantityPath);
        boolean hover=quantityEditable(node) && field.contains(contentX(mx),contentY(my));
        g.fill(field.x(),field.y(),field.x()+field.width(),field.y()+field.height(),editing?0xFF303D50:visual.badgeBackground());
        frame(g,field.x(),field.y(),field.width(),field.height(),node.cycle?CANVAS_EDGE:editing||hover?ACCENT:BORDER);
        String count=editing?quantityText+"|":Long.toString(node.demand);
        float size=Math.min(0.85F,(field.width()-8F)/Math.max(1,font.width(count)));
        int tx=field.x()+Math.round((field.width()-font.width(count)*size)/2),ty=field.y()+4;
        if(editing && quantityFresh)g.fill(field.x()+2,field.y()+2,field.x()+field.width()-2,field.y()+field.height()-2,0xFF466887);
        g.pose().pushPose();g.pose().translate(tx,ty,30);g.pose().scale(size,size,1);
        g.drawString(font,count,0,0,node.cycle?0xFFAAAAAA:editing?0xFFFFFFFF:visual.amountColor(),false);g.pose().popPose();
    }
    private MNode quantityAt(double mx,double my) {
        if(suggestionsVisible() && inside(mx,my,suggestionX,suggestionY,suggestionW,suggestionH))return null;
        var node=materialAt(mx,my);
        return node!=null && RouteQuantityField.of(node.x,node.y,node.w).contains(contentX(mx),contentY(my))?node:null;
    }

    private long forcedBatches(RNode recipe) {return RouteGraphMath.runs(Math.max(1,recipe.parent.demand),RouteGraphMath.outputAmount(recipe.choice,recipe.parent.material.key));}
    private String recipeMachineId(RNode recipe) {
        var value=branchDrafts.get(recipe.parent.path);
        if(value!=null)return value.recipeId();
        // Native AE2 pattern identities do not identify a JEI machine. Never guess from inputs or outputs.
        return recipe.choice.id().startsWith("jei:")?recipe.choice.id().substring(4):null;
    }
    private List<RouteView.PatternSource> recipeSources(RNode recipe) {
        return RoutePatternPresentation.sources(recipe.choice,recipe.parent.material.reply);
    }
    private void drawRecipePort(GuiGraphics g,MNode node,int mx,int my) {
        var port=RouteMaterialPort.of(node.x,node.y,node.w);int x=port.x(),y=port.y();
        g.fill(x,y,x+18,y+18,node.recipe!=null && selectedRecipe==node.recipe?0xFFC6C6D4:0xFFE4DFEB);frame(g,x,y,18,18,BORDER);
        if(node.recipe==null) {g.renderItem(appeng.core.definitions.AEItems.BLANK_PATTERN.stack(),x+1,y+1);return;}
        var recipe=node.recipe;
        boolean blocked=nodeMenu || draggedMaterial!=null || workbenchVisible() && workbench!=null && workbench.contains(mx,my);
        int hx=blocked?-1000000:(int)contentX(mx),hy=blocked?-1000000:(int)contentY(my);
        if(recipeMachineId(recipe)==null)nativeMachines.draw(g,node.path,recipeSources(recipe),x,y,hx,hy,ticks);
        else if(browser!=null)browser.renderRecipeBadge(recipeMachineId(recipe),g,x,y,hx,hy);
        else g.renderItem(appeng.core.definitions.AEItems.PROCESSING_PATTERN.stack(),x+1,y+1);
    }
    private String emcCostLabel(MNode node) {
        var draft=branchDrafts.get(node.path);
        if(RouteEmcRules.isEmc(draft))return "EMC · 转换桌 "+RouteEmcRules.cost(RouteEmcRules.unit(draft),node.demand);
        var costs=node.children.stream().filter(child->child.inlineResource)
                .map(child->child.material.name+" "+RouteGraphMath.format(child.demand)).toList();
        return costs.isEmpty()?null:"网络 "+String.join(" / ",costs);
    }

    private void drawNodeTooltip(GuiGraphics g,int mx,int my) {
        if(nodeMenu || draggedMaterial!=null || suggestionsVisible() || browser!=null && browser.draggingIngredient() || workbenchVisible() && workbench!=null && workbench.contains(mx,my)) {recipeHover.clear();return;}
        if(recipeHover.contains(mx,my)) {recipeHover.render(g,mx,my);return;}
        var recipe=recipeAt(mx,my);
        if(recipe!=null) {
            var value=branchDrafts.get(recipe.parent.path);
            int x=canvasLeft+Math.round((panX+recipe.x)*zoom),y=canvasTop+18+Math.round((panY+recipe.y)*zoom);
            recipeHover.offer(recipe.parent.path,recipe.parent.material.renderKey,recipeMachineId(recipe),recipe.choice,value,
                    x,y,Math.max(1,Math.round(18*zoom)),Math.max(1,Math.round(18*zoom)),width,height);
            recipeHover.render(g,mx,my);
            return;
        }
        recipeHover.clear();
        var node=materialAt(mx,my);
        if(node==null)return;
        var lines=new ArrayList<Component>();
        if(quantityAt(mx,my)!=null) {
            lines.add(Component.literal(node.material.name+" · 数量 "+(node.path.equals(quantityPath)?quantityText:node.demand)));
            lines.add(Component.literal(quantityEditable(node)?"点击输入；滚轮按批，Shift 单个；Ctrl+滚轮缩放":"数量只读，由最终产物需求与配方自动推导"));
            if(quantityEditable(node))lines.add(Component.literal("Enter 确认，Esc 取消"));
            g.renderComponentTooltip(font,lines,mx,my);return;
        }
        if(statusAt(mx,my)!=null) {drawStatusTooltip(g,node,mx,my);return;}
        lines.add(Component.literal(node.material.name));
        lines.add(Component.literal("需求："+node.demand+(node.material.renderKey instanceof AEFluidKey?" mB":" 个")));
        lines.add(Component.literal("底部状态行：悬停详情 / 点击分支菜单"));
        g.renderComponentTooltip(font,lines,mx,my);
    }

    private void drawInspector(GuiGraphics g, int mx, int my) {
        choiceHits.clear();
        actionHits.clear();
        MNode node = selectedNode == null ? root : selectedNode;
        if (node == null) {
            text(g, "选中的材料", panelLeft + 6, canvasTop + 7, panelW - 12, TEXT);
            text(g, "设置生产目标后，这里会显示配方与原料。", panelLeft + 6,
                    canvasTop + 24, panelW - 12, MUTED);
            return;
        }
        if (selectedRecipe != null) {
            drawRecipeInspector(g, node, selectedRecipe, mx, my);
        } else {
            drawMaterialInspector(g, node, mx, my);
        }
        drawActions(g, mx, my);
    }

    private void drawMaterialInspector(GuiGraphics g, MNode node, int mx, int my) {
        int x = panelLeft + 6;
        int right = panelRight - 8;
        int max = right - x;
        if(compact())text(g,node.material.name,x,canvasTop+2,max,TEXT);
        else {
            badge(g,"材料",x,canvasTop+5,BLUE);
            text(g,node.material.name,x+34,canvasTop+5,max-34,TEXT);
        }
        int tabW=Math.min(66,(max-8)/3),tabY=canvasTop+(compact()?14:20),tabH=compact()?14:18;
        action(g,"JEI配方",x,tabY,tabW,tabH,()->{jeiTab=true;choiceScroll=0;if(browser!=null && RouteEmcRules.isEmc(browser.currentDraft()))browser.selectIndex(0);});
        action(g,"网络样板",x+tabW+4,tabY,tabW,tabH,()->{jeiTab=false;choiceScroll=0;});
        action(g,"EMC",x+2*(tabW+4),tabY,tabW,tabH,()->{
            jeiTab=true;
            if(browser!=null)browser.select(node.material.renderKey);
            if(browser==null || !browser.selectEmc())status="此材料没有可用的 ProjectE EMC 值；不能使用 EMC 转换";
        });

        if(jeiTab) {
            if(browser==null) text(g,"JEI 尚未就绪",x,canvasTop+70,max,MUTED);
            else { browser.select(node.material.renderKey);browser.demand(node.demand); browser.render(g,x,uiLayout().recipeTop(),max,
                    Math.max(0,inspectorBottom()-uiLayout().recipeTop()-22),mx,my); }
            var value=branchDrafts.get(node.path);
            action(g,encodingLabel(node),x,inspectorBottom()-20,max,18,this::encodeSelected);
            return;
        }
        text(g, "网络样板（点击展开，不立即修改合成）", x, canvasTop + (compact()?31:68), max, MUTED);
        int listTop = canvasTop + (compact()?42:82);
        int listBottom = inspectorBottom() - 4;
        List<ChoiceRow> rows = choiceRows(node);
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        int maxScroll = Math.max(0, rows.size() - visible);
        choiceScroll = Math.max(0, Math.min(choiceScroll, maxScroll));
        if (node.material.reply == null && !node.material.loading) {
            text(g, "没有收到配方数据。", x, listTop + 4, max, MUTED);
        } else if (rows.isEmpty()) {
            text(g, "没有已编码样板。", x, listTop + 4, max, MUTED);
            text(g, "切换到 JEI 配方，从这里创建样板。", x, listTop + 17, max, FAINT);
        }
        for (int row = 0; row < visible && choiceScroll + row < rows.size(); row++) {
            int index = choiceScroll + row;
            int y = listTop + row * ROW_H;
            drawChoiceRow(g, node, rows.get(index), x, y, right - x, mx, my);
        }
        if (maxScroll > 0) {
            int track = listBottom - listTop;
            int thumb = Math.max(12, track * visible / rows.size());
            int thumbY = listTop + (track - thumb) * choiceScroll / maxScroll;
            g.fill(right - 2, listTop, right, listBottom, CANVAS_EDGE);
            g.fill(right - 3, thumbY, right - 1, thumbY + thumb, ACCENT);
        }
    }

    private void drawRecipeInspector(GuiGraphics g, MNode node, RNode recipe, int mx, int my) {
        int x = panelLeft + 6;
        int right = panelRight - 8;
        int max = right - x;
        if(recipe.draft && browser!=null) {
            text(g,node.material.name,x,canvasTop+5,max,TEXT);
            var value=branchDrafts.get(node.path);
            browser.select(node.material.renderKey);
            if(value!=null)browser.focusRecipe(value.recipeId());
            browser.demand(node.demand);
            browser.render(g,x,canvasTop+22,max,inspectorBottom()-canvasTop-48,mx,my);
            action(g,encodingLabel(node),x,inspectorBottom()-20,max,18,this::encodeSelected);
            return;
        }
        badge(g, recipe.draft ? "JEI配方" : "配方", x, canvasTop + 5, recipe.draft ? PURPLE : AMBER);
        text(g, recipe.draft ? "从 JEI 导入" : "你 ME 网络里的编码样板",
                x + (recipe.draft ? 48 : 34), canvasTop + 5,
                max - (recipe.draft ? 48 : 34), TEXT);
        text(g, "服务于材料：" + node.material.name, x, canvasTop + 18, max, MUTED);
        var currentDraft=branchDrafts.get(node.path);
        String sourceLine = recipe.draft
                ? "JEI · " + (currentDraft==null?"":currentDraft.category()) + " · 待编码"
                : "应用 ≥"+forcedBatches(recipe)+" 批 · "+node.path;
        text(g, sourceLine, x, canvasTop + 31, max, recipe.draft ? PURPLE : ACCENT);
        long out = RouteGraphMath.outputAmount(recipe.choice, node.material.key);
        text(g, "产出：" + RouteGraphMath.format(out) + "× " + node.material.name
                + " / 单次样板", x, canvasTop + 44, max, TEXT);
        var sources=recipeSources(recipe);
        String origin=sources.isEmpty()?"样板来源：暂无可见在线供应器":
                "样板来源："+sources.getFirst().name()+" · 槽 "+(sources.getFirst().slot()+1)
                        +(sources.size()>1?"（另有 "+(sources.size()-1)+" 处）":"");
        text(g,origin,x,canvasTop+58,max,MUTED);
        text(g, "输入原料", x, canvasTop + 76, max, MUTED);
        int y = canvasTop + 89;
        int lineMax = inspectorBottom() - 46;
        for (var stack : recipe.choice.inputs()) {
            if (y > lineMax) { text(g, "…还有更多原料", x, y, max, FAINT); break; }
            icon(g, stackKey(stack), x, y - 2);
            text(g, RouteGraphMath.format(stack.amount()) + "× " + stack.name(), x + 20, y,
                    max - 20, TEXT);
            y += 14;
        }
        if (recipe.choice.alternatives()) {
            text(g, "含替代材料；本画布按编码主变体展示。", x, y, max, FAINT);
        }
        int by = inspectorBottom() - 22;
        if (recipe.draft) {
            action(g,"生成样板",x,by,Math.min(100,max),16,this::encodeSelected);
        } else {
            action(g,"应用到合成",x,by,78,16,()->{
                if(recipe.choice.id().startsWith("plan:"))status="原始计划尚未匹配到网络样板，不能应用";
                else send("lock",node.material.renderKey,recipe.choice.id());
            });

        }
    }

    private List<ChoiceRow> choiceRows(MNode node) {
        var rows = new ArrayList<ChoiceRow>();
        if (node.material.reply != null) {
            for (var choice : node.material.reply.choices()) {
                rows.add(new ChoiceRow(choice, false, matchesDraft(choice)));
            }
        }
        if (draftFor(node.material.key) != null) {
            rows.add(new ChoiceRow(draftFor(node.material.key), true, true));
        }
        return rows;
    }

    private void drawChoiceRow(GuiGraphics g, MNode node, ChoiceRow row, int x, int y,
                               int w, int mx, int my) {
        RouteView.Choice choice = row.choice;
        boolean locked = node.material.reply != null && choice.id().equals(node.material.reply.locked());
        boolean banned = node.material.reply != null && node.material.reply.banned().contains(choice.id());
        boolean hover = inside(mx, my, x, y, w, ROW_H - 2);
        int bg = row.draft ? 0xFFC6C6D4 : banned ? 0xFFD4B8B8 : locked ? 0xFFB8C6D4
                : hover ? 0xFFC6C6D4 : 0xFFADB0C4;
        g.fill(x, y, x + w, y + ROW_H - 2, bg);
        frame(g, x, y, w, ROW_H - 2, locked ? ACCENT : banned ? RED : row.draft ? PURPLE : BORDER);
        String title = "→ " + RouteGraphMath.format(RouteGraphMath.outputAmount(choice, node.material.key))
                + "× " + node.material.name;
        icon(g, node.material.renderKey, x + 4, y + 5);
        text(g, title, x + 23, y + 4, w - (row.draft ? 67 : 59), banned ? RED : TEXT);
        String tag = row.draft ? "预览" : locked ? "使用中" : banned ? "已停用"
                : row.matches ? "JEI相同" : "可用";
        int tw = font.width(tag) + 6;
        g.fill(x + w - tw - 4, y + 3, x + w - 4, y + 14,
                locked ? 0xFFCCDADA : banned ? 0xFFE6CACA : row.draft ? 0xFFDDD0E8 : 0xFFBEBEBE);
        text(g, tag, x + w - tw - 1, y + 4, tw, locked ? ACCENT : banned ? RED : row.draft ? PURPLE : MUTED);
        String summary = "输入 " + RouteScreen.summary(choice.inputs());
        text(g, summary, x + 23, y + 17, w - 28, FAINT);
        if (!row.draft) {
            choiceHits.add(new ChoiceHit(x, y, x + w, y + ROW_H - 2, node, choice));
        }
    }

    private void drawActions(GuiGraphics g, int mx, int my) {
        for (var hit : actionHits) {
            boolean hover = inside(mx, my, hit.x1, hit.y1, hit.x2 - hit.x1, hit.y2 - hit.y1);
            g.fill(hit.x1, hit.y1, hit.x2, hit.y2, hover ? BUTTON_HOVER : BUTTON_FACE);
            frame(g, hit.x1, hit.y1, hit.x2 - hit.x1, hit.y2 - hit.y1,
                    hover ? ACCENT : CANVAS_EDGE);
            int tw = font.width(hit.label);
            text(g, hit.label, hit.x1 + (hit.x2 - hit.x1 - tw) / 2, hit.y1 + 4,
                    hit.x2 - hit.x1 - 2, TEXT);
        }
    }

    private int action(GuiGraphics g, String label, int x, int y, int w, int h, Runnable run) {
        actionHits.add(new ActionHit(x, y, x + w, y + h, label, run));
        return x + w;
    }

    private void drawFooter(GuiGraphics g) {
        int color = status != null && (status.startsWith("操作失败") || status.contains("失败")) ? RED : MUTED;
        String line=status == null ? "" : status;
        text(g, line, windowBounds().left()+10, windowBounds().bottom()-18, windowBounds().width()-20, color);
    }

    private void drawWire(GuiGraphics g) {
        if (wireFrom == null) {
            if(browser!=null && browser.draggingIngredient() && activeMaterial()!=null) {
                var node=activeMaterial();int sx=screenX(node)+Math.round(node.w*zoom/2),sy=screenY(node)+Math.round(node.h*zoom);
                int ex=(int)wireX,ey=(int)wireY,mid=sy+(ey-sy)/2;
                g.pose().pushPose();g.pose().translate(0,0,250);
                drawV(g,sx,Math.min(sy,mid),Math.max(sy,mid),GOLD,true);drawH(g,sx,ex,mid,GOLD,true);
                drawV(g,ex,Math.min(mid,ey),Math.max(mid,ey),GOLD,true);g.pose().popPose();
            }
            return;
        }
        int x1 = screenX(wireFrom) + Math.round((wireFrom.w - 6) * zoom);
        int y1 = screenY(wireFrom) + Math.round(wireFrom.h / 2F * zoom);
        int x2 = (int) wireX;
        int y2 = (int) wireY;
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps > 0) {
            for (int i = 0; i <= steps; i += 4) {
                int x = x1 + dx * i / steps;
                int y = y1 + dy * i / steps;
                g.fill(x - 1, y - 1, x + 2, y + 2, GOLD);
            }
        }
        g.fill(x2 - 3, y2 - 3, x2 + 3, y2 + 3, GOLD);
    }

    private boolean suggestionsVisible() {
        return itemId != null && itemId.isFocused() && !suggestions.isEmpty();
    }

    private int suggestionPages() {
        return Math.max(1, (suggestions.size() + 5) / 6);
    }

    private void drawSuggestions(GuiGraphics g, int mx, int my) {
        suggestionHits.clear();
        if (!suggestionsVisible()) return;
        int perPage = 6;
        suggestionPage = Math.max(0, Math.min(suggestionPage, suggestionPages() - 1));
        int w = Math.max(itemId.getWidth() + 42, 250);
        int x = itemId.getX();
        w=Math.min(w,windowBounds().width()-16);
        x=Math.max(windowBounds().left()+8,Math.min(x,contentRight()-8-w));
        int y = itemId.getY() + itemId.getHeight() + 2;
        int rows = Math.min(perPage, suggestions.size() - suggestionPage * perPage);
        int h = 18 + rows * 20 + 2;
        suggestionX = x; suggestionY = y; suggestionW = w; suggestionH = h;
        g.fill(x, y, x + w, y + h, 0xFFCBCCD4);
        frame(g, x, y, w, h, ACCENT);
        String head = "结果 " + (suggestionPage * perPage + 1) + "–"
                + (suggestionPage * perPage + rows) + " / " + suggestions.size()
                + "  滚轮或箭头翻页";
        text(g, head, x + 4, y + 4, w - 44, MUTED);
        int ax = x + w - 40, ay = y + 2;
        g.fill(ax, ay, ax + 18, ay + 14, BUTTON_FACE); frame(g, ax, ay, 18, 14, BORDER);
        text(g, "‹", ax + 6, ay + 3, 12, TEXT);
        g.fill(ax + 20, ay, ax + 38, ay + 14, BUTTON_FACE); frame(g, ax + 20, ay, 18, 14, BORDER);
        text(g, "›", ax + 26, ay + 3, 12, TEXT);
        for (int i = 0; i < rows; i++) {
            Item item = suggestions.get(suggestionPage * perPage + i);
            int rowY = y + 18 + i * 20;
            boolean hover = inside(mx, my, x + 1, rowY, w - 2, 19);
            if (hover) g.fill(x + 1, rowY, x + w - 1, rowY + 19, BUTTON_HOVER);
            icon(g, AEItemKey.of(item), x + 3, rowY + 2);
            text(g, new ItemStack(item).getHoverName().getString(), x + 23, rowY + 5,
                    w - 28, TEXT);
            suggestionHits.add(new SuggestionHit(x + 1, rowY, x + w - 1, rowY + 19, item));
        }
    }

    private void badge(GuiGraphics g, String label, int x, int y, int color) {
        int w = font.width(label) + 8;
        g.fill(x, y - 1, x + w, y + 12, BUTTON_FACE);
        frame(g, x, y - 1, w, 13, color);
        text(g, label, x + 4, y, w - 4, color);
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void bevel(GuiGraphics g,int x,int y,int w,int h) {
        g.fill(x+1,y+1,x+w-1,y+2,0xFFFFFFFF);
        g.fill(x+1,y+1,x+2,y+h-1,0xFFFFFFFF);
        g.fill(x+1,y+h-2,x+w-1,y+h-1,0xFF696D88);
        g.fill(x+w-2,y+1,x+w-1,y+h-1,0xFF696D88);
    }

    private void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void text(GuiGraphics g, String value, int x, int y, int maxWidth, int color) {
        if (value == null || value.isEmpty()) return;
        g.drawString(font, font.plainSubstrByWidth(value, Math.max(1, maxWidth)), x, y, color, false);
    }

    private AEKey stackKey(RouteView.Stack stack) {
        try {
            return stack.decode(Minecraft.getInstance().level.registryAccess());
        } catch (Exception e) {
            return null;
        }
    }

    /** Draw-only AE2 button: click handling stays vanilla; only the face is re-skinned. */
    private static final class AeButton extends Button {
        private final boolean selected;
        AeButton(int x,int y,int w,int h,Component message,Button.OnPress onPress,boolean selected) {
            super(x,y,w,h,message,onPress,DEFAULT_NARRATION);
            this.selected=selected;
        }
        @Override
        protected void renderWidget(GuiGraphics g,int mx,int my,float dt) {
            int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            int face=!active?0xFF9A9FB4:isHoveredOrFocused()?BUTTON_HOVER:BUTTON_FACE;
            g.fill(x,y,x+w,y+h,face);
            g.fill(x+1,y+1,x+w-1,y+2,0xFFFFFFFF);
            g.fill(x+1,y+1,x+2,y+h-1,0xFFFFFFFF);
            g.fill(x+1,y+h-2,x+w-1,y+h-1,0xFF696D88);
            g.fill(x+w-2,y+1,x+w-1,y+h-1,0xFF696D88);
            g.fill(x,y,x+w,y+1,0xFF373737);
            g.fill(x,y+h-1,x+w,y+h,0xFF373737);
            g.fill(x,y,x+1,y+h,0xFF373737);
            g.fill(x+w-1,y,x+w,y+h,0xFF373737);
            if(selected)g.fill(x+2,y+h-3,x+w-2,y+h-1,0xFF3E6B8A);
            g.drawCenteredString(Minecraft.getInstance().font,getMessage(),x+w/2,y+(h-8)/2,active?0xFF2D2F38:0xFF6A6E82);
        }
    }

    private void icon(GuiGraphics g, AEKey key, int x, int y) {
        if(key!=null)appeng.api.client.AEKeyRendering.drawInGui(Minecraft.getInstance(),g,x,y,key);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!usable()) return super.mouseClicked(mx,my,button);
        if(recipeHover.contains(mx,my))return true;
        if(browser!=null && browser.clickMachineOverlay(mx,my,button))return true;
        if(nodeMenu) {
            for(var hit:nodeMenuHits)if(inside(mx,my,hit.x1,hit.y1,hit.x2-hit.x1,hit.y2-hit.y1)) {if(button==0)hit.action.run();return true;}
            if(inside(mx,my,nodeMenuLeft,nodeMenuTop,nodeMenuWidth,nodeMenuHeight))return true;
            nodeMenu=false; // Close without swallowing a drag beginning outside this popup.
        }
        if(workbenchVisible() && workbench!=null && workbench.click(mx,my,button)) {
            if(itemId!=null)itemId.setFocused(false);
            cancelQuantity();
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            if (itemId != null && itemId.isFocused()) onSearchChanged(itemId.getValue());
            return true;
        }
        if(mx<windowBounds().left() || mx>=contentRight() || my<windowBounds().top() || my>=windowBounds().bottom())return false;
        if (button == 2) {
            MNode hit = materialAt(mx, my);
            if (hit != null) startQuantityEdit(hit);
            return hit != null;
        }
        if (button != 0 && button != 1) return false;
        // A short inspector can put the picker over its footer: the visible modal wins hit testing.
        if(nativeRecipeVisible() && browser.pickingRecipe() && browser.click(mx,my,button,this::expandDraft))return true;
        var quantity=quantityAt(mx,my);
        if(button==0 && quantity!=null) {
            if(!quantity.path.equals(quantityPath))startQuantityEdit(quantity);return true;
        }
        if(quantityPath!=null)cancelQuantity();
        var branchStatus=statusAt(mx,my);
        if(button==0 && branchStatus!=null) {
            openNodeMenu(branchStatus,mx,my,false);menuSection(RouteNodeMenu.Page.BRANCH);return true;
        }

        if (suggestionsVisible() && inside(mx,my,suggestionX+suggestionW-40,suggestionY+2,18,14)) {
            suggestionPage = Math.max(0, suggestionPage - 1); return true;
        }
        if (suggestionsVisible() && inside(mx,my,suggestionX+suggestionW-20,suggestionY+2,18,14)) {
            suggestionPage = Math.min(suggestionPages() - 1, suggestionPage + 1); return true;
        }
        for (var hit : suggestionHits) {
            if (inside(mx, my, hit.x1, hit.y1, hit.x2 - hit.x1, hit.y2 - hit.y1)) {
                itemId.setFocused(false);
                checkpoint();
                setTarget(AEItemKey.of(hit.item), 0, "搜索", "");
                setFieldText(targetName(target));
                return true;
            }
        }

        for (var hit : actionHits) {
            if (inside(mx, my, hit.x1, hit.y1, hit.x2 - hit.x1, hit.y2 - hit.y1)) {
                if (button == 0) hit.action.run();
                return true;
            }
        }

        if(activeMaterial()!=null && nativeRecipeVisible() && browser.click(mx,my,button,this::expandDraft)) {setDragging(true);return true;}

        for (int i = choiceHits.size() - 1; i >= 0; i--) {
            var hit = choiceHits.get(i);
            if (!inside(mx, my, hit.x1, hit.y1, hit.x2 - hit.x1, hit.y2 - hit.y1)) continue;
            selectedNode = hit.node;
            selectedKey = hit.node.material.key;
            selectedRecipe = null;
            if(button==0) {
                checkpoint(); branchEdits.expandAll(hit.node.path);branches.put(hit.node.path,hit.choice);branchDrafts.remove(hit.node.path);
                rebuildGraph(); status="已展开网络样板；在配方详情中应用到实际合成";
            }
            return true;
        }

        MNode material = materialAt(mx, my);
        if (material != null) {
            jeiTab=true;
            choiceScroll=0;
            selectedNode = material;
            selectedKey = material.material.key;
            selectedRecipe = null;
            if(recipePortHit(material,mx,my) && button==0 && material.recipe!=null) {
                selectedRecipe=material.recipe;if(narrow()){narrowInspector=true;buildWidgets();}return true;
            }
            if (RouteNodeInteraction.press(button,recipePortHit(material,mx,my))==RouteNodeInteraction.Action.RECIPES) {
                openNodeMenu(material,mx,my,true);
            } else {
                if(browser!=null)browser.clearMachineOverlay();
                draggedMaterial = material;
                dragGeometry=graphGeometry();
                nodeDragMoved=false;
                pendingDragState = captureState();
                dragMouseX = mx;
                dragMouseY = my;
                dragNodeX = material.x;
                dragNodeY = material.y;
                setDragging(true);
            }
            return true;
        }
        if(narrow() && narrowInspector)return true;
        if (inside(mx, my, canvasLeft, canvasTop + 18, canvasWidth(), canvasBottom - canvasTop - 18)) {
            panning = true;
            autoCenter = false;
            panMouseX = mx;
            panMouseY = my;
            panStartX = panX;
            panStartY = panY;
            setDragging(true);
            return true;
        }
        return false;
    }

    private MNode materialAt(double mx, double my) {
        if(narrow() && narrowInspector)return null;
        if (!inside(mx, my, canvasLeft, canvasTop + 16, canvasWidth(), canvasBottom - canvasTop - 16)) return null;
        double x = contentX(mx), y = contentY(my);
        for (int i = materialNodes.size() - 1; i >= 0; i--) {
            var node = materialNodes.get(i);
            if (!node.inlineResource && inside(x, y, node.x, node.y, node.w, node.h)) return node;
        }
        return null;
    }

    private RNode recipeAt(double mx, double my) {
        if(narrow() && narrowInspector)return null;
        if (!inside(mx, my, canvasLeft, canvasTop + 16, canvasWidth(), canvasBottom - canvasTop - 16)) return null;
        double x = contentX(mx), y = contentY(my);
        for (int i = recipeNodes.size() - 1; i >= 0; i--) {
            var node = recipeNodes.get(i);
            if (inside(x, y, node.x, node.y, node.w, node.h)) return node;
        }
        return null;
    }

    private boolean recipePortHit(MNode node,double mx,double my) {
        return RouteMaterialPort.of(node.x,node.y,node.w).contains(contentX(mx),contentY(my));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if(workbenchVisible() && workbench!=null && workbench.drag(mx,my))return true;
        if(draggedMaterial==null && !panning && nativeRecipeVisible() && browser.drag(mx,my,button,dx,dy))return true;
        if (wireFrom != null) {
            wireX = mx;
            wireY = my;
            return true;
        }
        if (draggedMaterial != null) {
            nodeDragMoved=RouteNodeInteraction.moved(nodeDragMoved,mx-dragMouseX,my-dragMouseY);
            if(!nodeDragMoved)return true;
            applyGeometry(RouteTreeGeometry.drag(dragGeometry,draggedMaterial.path,
                    (int)Math.round((mx-dragMouseX)/zoom),(int)Math.round((my-dragMouseY)/zoom)));
            return true;
        }
        if (panning) {
            panX = panStartX + (int) Math.round((mx - panMouseX) / zoom);
            panY = panStartY + (int) Math.round((my - panMouseY) / zoom);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        setDragging(false);
        if(browser!=null && browser.release(mx,my,button,this::dropRecipeIngredient))return true;
        if(workbenchVisible() && workbench!=null && workbench.release(mx,my))return true;
        if (wireFrom != null) {
            ChoiceHit hit = choiceAt(mx, my);
            if (hit == null) {
                status = "没有连上：拖到右边同一材料的配方卡片上";
            } else if (!hit.node.material.key.equals(wireFrom.material.key)) {
                status = "材料不匹配：只能连到当前材料的配方";
            } else {
                checkpoint(); branches.put(wireFrom.path,hit.choice); rebuildGraph();
            }
            wireFrom = null;
            return true;
        }
        if (draggedMaterial != null) {
            nodeDragMoved=RouteNodeInteraction.moved(nodeDragMoved,mx-dragMouseX,my-dragMouseY);
            if(RouteNodeInteraction.release(nodeDragMoved,false)==RouteNodeInteraction.Action.REMOVE) {
                openNodeMenu(draggedMaterial,mx,my,false);
                draggedMaterial=null;pendingDragState=null;
                return true;
            }
            if(!graphGeometry().equals(dragGeometry))pushUndo(pendingDragState);
            saveGeometryOffsets();dragGeometry=List.of();
            draggedMaterial = null;
            pendingDragState = null;
            return true;
        }
        if (panning) {
            panning = false;
            pendingDragState = null;
            return true;
        }
        pendingDragState = null;
        return false;
    }

    private ChoiceHit choiceAt(double mx, double my) {
        for (var hit : choiceHits) {
            if (inside(mx, my, hit.x1, hit.y1, hit.x2 - hit.x1, hit.y2 - hit.y1)) return hit;
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if(recipeHover.contains(mx,my))return true;
        if(browser!=null && browser.scrollMachineOverlay(mx,my,scrollY))return true;
        if (suggestionsVisible() && inside(mx,my,suggestionX,suggestionY,suggestionW,suggestionH)) {
            if (scrollY != 0) suggestionPage = Math.max(0, Math.min(suggestionPages()-1,
                    suggestionPage + (scrollY < 0 ? 1 : -1)));
            return true;
        }
        if(nodeMenu) {if(nodeMenuRecipes && scrollY!=0)moveNodeMenuPage(scrollY<0?1:-1);return true;}
        if(workbenchVisible() && workbench!=null && workbench.scroll(mx,my,scrollY))return true;
        if (scrollY==0 && scrollX==0)return false;
        if ((!narrow() || narrowInspector) && inside(mx, my, panelLeft, canvasTop, panelW, canvasBottom - canvasTop)) {
            if(nativeRecipeVisible()) {
                if(!browser.scroll(mx,my,scrollX,scrollY) && scrollY!=0)browser.page(scrollY<0?1:-1);return true;
            }
            if(scrollY==0)return true;
            choiceScroll += scrollY < 0 ? 1 : -1;
            return true;
        }
        if (inside(mx, my, canvasLeft, canvasTop, canvasWidth(), canvasBottom - canvasTop)) {
            var hit=materialAt(mx,my);
            if(RouteQuantityField.wheelEdits(quantityAt(mx,my)!=null && quantityEditable(hit),hasControlDown(),scrollX!=0,hit!=null && hit.cycle)) {
                if(scrollY!=0)adjustQuantity(hit,scrollY>0?1:-1,hasShiftDown());return true;
            }
            var intent=RouteWheelIntent.of(hasControlDown(),hasShiftDown(),hasAltDown(),quantityEditable(hit),
                    scrollX!=0,com.aecraftroute.addon.config.CraftRouteConfig.scrollEditsQuantity());
            if(intent==RouteWheelIntent.QUANTITY) {
                if(scrollY!=0)adjustQuantity(hit,scrollY>0?1:-1,hasShiftDown());return true;
            }
            autoCenter=false;
            if(intent==RouteWheelIntent.PAN) {
                if(scrollX==0 && !hasShiftDown() && com.aecraftroute.addon.config.CraftRouteConfig.scrollEditsQuantity())
                    panY+=(int)Math.round(scrollY*18);
                else panX+=(int)Math.round((scrollX!=0?scrollX:scrollY)*18);
            }
            else if(scrollY!=0) {
                double cx=contentX(mx),cy=contentY(my);
                zoom=Math.max(0.25F,Math.min(2.0F,zoom+(scrollY>0?0.1F:-0.1F)));
                panX=(int)Math.round((mx-canvasLeft)/zoom-cx);
                panY=(int)Math.round((my-canvasTop-18)/zoom-cy);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if(workbenchVisible() && workbench!=null && (workbench.charTyped(codePoint,modifiers) || workbench.focused()))return true;
        if (quantityPath != null && (itemId == null || !itemId.isFocused())) {
            if (codePoint >= '0' && codePoint <= '9') {
                if (quantityFresh) { quantityText = ""; quantityFresh = false; }
                if (quantityText.length() < 17) quantityText += codePoint;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(keyCode==GLFW.GLFW_KEY_ESCAPE && nodeMenu) {backNodeMenu();return true;}
        if(workbenchVisible() && workbench!=null && (workbench.keyPressed(keyCode,scanCode,modifiers) || workbench.focused()))return true;
        if (quantityPath != null && (itemId == null || !itemId.isFocused())) {
            if(keyCode==GLFW.GLFW_KEY_A && hasControlDown())quantityFresh=true;
            else if(keyCode==GLFW.GLFW_KEY_V && hasControlDown()) {
                String clipboard=Minecraft.getInstance().keyboardHandler.getClipboard().strip();
                if(clipboard.matches("[0-9]{1,17}")) {quantityText=clipboard;quantityFresh=false;}
                else status="数量只接受 1 至 17 位数字";
            }
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) commitQuantity();
            else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {cancelQuantity();status="已取消数量编辑";}
            else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (quantityFresh) { quantityText = ""; quantityFresh = false; }
                else if (!quantityText.isEmpty()) quantityText = quantityText.substring(0, quantityText.length() - 1);
            }
            return true;
        }
        if(itemId==null || !itemId.isFocused()) {
            if(nativeRecipeVisible() && (workbench==null || !workbench.hovered()) && browser.key(keyCode,scanCode))return true;
            if(keyCode==GLFW.GLFW_KEY_DELETE && selectedNode!=null) {
                openNodeMenu(selectedNode,screenX(selectedNode),screenY(selectedNode),false);return true;
            }
            if(keyCode==GLFW.GLFW_KEY_Z && hasControlDown()) {undoEdit();return true;}
            if(keyCode==GLFW.GLFW_KEY_HOME) {autoArrange();return true;}
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && itemId != null && itemId.isFocused()) {
            setTargetFromField();
            return true;
        }
        if(keyCode==GLFW.GLFW_KEY_ESCAPE && itemId!=null && itemId.isFocused()) {
            itemId.setFocused(false);
            suggestions=List.of();
            return true;
        }
        if(keyCode==GLFW.GLFW_KEY_ESCAPE) {onClose();return true;}
        return super.keyPressed(keyCode,scanCode,modifiers);
    }

    private void saveDraft() {
        if (draft == null) {
            status = "现在没有从 JEI 导入的配方";
            return;
        }
        try {
            RouteDrafts.save(draft);
            status = "已保存到本地；它仍然不能直接交给 CPU";
        } catch (Exception e) {
            status = e.getMessage();
        }
    }

    @Override
    public void onClose() {
        if (clientHandler != null && RouteNetwork.clientHandler == clientHandler)
            RouteNetwork.clientHandler = previousClientHandler;
        Minecraft.getInstance().setScreen(parent);
    }

    void setClientHandlers(java.util.function.Consumer<RouteNetwork.Response> previous,
                           java.util.function.Consumer<RouteNetwork.Response> current) {
        previousClientHandler = previous;
        clientHandler = current;
    }

    /** Area where JEI can drop an item/fluid to make it the target. */
    public net.minecraft.client.renderer.Rect2i getDropArea() {
        return new net.minecraft.client.renderer.Rect2i(canvasLeft, canvasTop,
                Math.max(1, canvasWidth()), Math.max(1, (!workbenchVisible() || workbench==null?canvasBottom:workbench.dropBottom()) - canvasTop));
    }

    public void acceptDroppedIngredient(AEKey key) {
        if (key == null) return;
        suggestions = List.of();
        if(target!=null) {
            var node=activeMaterial();
            var value=browser!=null && jeiTab?browser.currentDraft():node==null?null:branchDrafts.get(node.path);
            if(value==null) {status="先选中父节点及其配方，再把该配方材料拖入；更换最终产物请用顶部目标框";return;}
            dropRecipeIngredient(value,key,canvasLeft+canvasWidth()/2.0,canvasTop+(canvasBottom-canvasTop)*0.7);
            return;
        }
        checkpoint(); setTarget(key, 0, "JEI 拖入", "");
        status = "已把 JEI 里的物品设为目标";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        pumpCanvasChunks();
        var expired=new ArrayList<Pending>();
        pending.entrySet().removeIf(e->{var request=e.getValue();
            boolean timedOut="apply".equals(request.action)?RouteRequestRules.expiredApply(ticks,request.started):RouteRequestRules.expired(ticks,request.started);
            if(timedOut) {expired.add(request);return true;}return false;});
        for(var request:expired) {
            if(request.key!=null) {var m=materials.get(keyId(request.key));if(m!=null){m.loading=false;
                if("query".equals(request.action))m.error="服务器未回应样板查询";}}
            if("preview".equals(request.action) || "apply".equals(request.action)) {
                // No verdict arrived. Release the dedupe gate so the next rebuild asks again
                // instead of leaving the authoritative panel blank for this target and amount.
                lastPreviewSignature=null;
                status="服务器未在预算内确认规划；请检查连接和服务端日志，不会自动应用";
            } else if("workbench".equals(request.action)) {
                status="样板缓存刷新未收到服务器回应，稍后自动重试";
            } else if(RouteRequestRules.mutation(request.action))status="服务器未在 10 秒内确认；未自动重试，请检查缓存后再操作";
        }
        if(expired.stream().anyMatch(p->"query".equals(p.action)))rebuildGraph();
        if(expandingJei) advanceJeiExpansion();
        if(browser!=null)browser.tick();
        recipeHover.tick();
        if(workbench!=null)workbench.tick();
        retryBeginIfDue();
        if(workbench!=null && ++workbenchTicks%40==0)workbench.refresh();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu.containerId != menuId) {
            mc.setScreen(null);
        }
    }

    /** Do bounded JEI work on the client thread, without querying network sample identities. */
    private void advanceJeiExpansion() {
        int work=0;
        while(work++<2) {
            MNode next=null;
            for(var node:materialNodes)if(!expansionVisited.contains(node.path)){next=node;break;}
            if(next==null) {
                expandingJei=false;expansionBrowser=null;
                long cycles=materialNodes.stream().filter(n->n.cycle).count();
                status="JEI 展开："+materialNodes.size()+" 个材料、"+recipeNodes.size()+" 个配方"
                        +(cycles>0?"；回环停止 "+cycles+" 处":"")
                        +(expansionErrors>0?"；JEI 读取失败 "+expansionErrors+" 处":"")
                        +"；仅预览，不绑定样板";
                return;
            }
            expansionVisited.add(next.path);
            if(next.cycle || branchDrafts.containsKey(next.path))continue;
            try {
                var found=expansionBrowser.lookupOutput(next.material.renderKey);
                if(found.failed()){expansionErrors++;continue;}
                var value=found.draft();if(value==null)continue;
                var choice=draftChoice(value);
                if(RouteGraphMath.outputAmount(choice,next.material.key)<=0){expansionErrors++;continue;}
                branches.put(next.path,choice);branchDrafts.put(next.path,value);
                rebuildGraph();
            } catch(RuntimeException error) {expansionErrors++;LOGGER.warn("JEI expansion failed at {}",next.path,error);}
        }
        status="正在展开 JEI 配方："+materialNodes.size()+" 个材料；不绑定样板";
    }

    public static String summary(List<RouteView.Stack> stacks) {
        return stacks.stream().map(s -> s.amount() + "×" + s.name())
                .reduce((a, b) -> a + " + " + b).orElse("无输入");
    }

    private boolean matchesDraft(RouteView.Choice choice) {
        if (draft == null) return false;
        try {
            return amounts(choice.inputs()).equals(amounts(draft.inputs()))
                    && amounts(choice.outputs()).equals(amounts(draft.outputs()));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sameAmounts(RouteView.Choice left, RouteView.Choice right) {
        try {
            return amounts(left.inputs()).equals(amounts(right.inputs()))
                    && amounts(left.outputs()).equals(amounts(right.outputs()));
        } catch (RuntimeException invalid) {
            // A stale third-party key must not crash the client reply/render path.
            return false;
        }
    }

    private Map<AEKey, Long> amounts(List<RouteView.Stack> stacks) {
        var result = new HashMap<AEKey, Long>();
        for (var stack : stacks) {
            var key=stack.decode(Minecraft.getInstance().level.registryAccess());
            if(key==null)throw new IllegalArgumentException("材料数据无法读取");
            result.merge(key,stack.amount(),Math::addExact);
        }
        return result;
    }

    private static final class Material {
        final AEKey renderKey;
        final String key;
        final String name;
        RouteView.Reply reply;
        boolean loading;
        String error;

        Material(AEKey renderKey, String key, String name) {
            this.renderKey = renderKey;
            this.key = key;
            this.name = name;
        }
    }

    private static final class MNode {
        final String path;
        final Material material;
        final long demand;
        final int depth;
        RouteGraphMath.Preview preview;
        RNode recipe;
        final List<MNode> children = new ArrayList<>();
        boolean cycle,inlineResource;
        String recipeError;
        int x, y, w, h, autoX, autoY, subtreeW;

        MNode(String path, Material material, long demand, int depth) {
            this.path = path;
            this.material = material;
            this.demand = demand;
            this.depth = depth;
        }
    }

    private static final class RNode {
        final MNode parent;
        final RouteView.Choice choice;
        final boolean draft;
        int x, y, w, h, autoX, autoY;

        RNode(MNode parent, RouteView.Choice choice, boolean draft) {
            this.parent = parent;
            this.choice = choice;
            this.draft = draft;
        }
    }

    private record Pending(AEKey key, String action,long started,String signature) {}
    private record ChoiceRow(RouteView.Choice choice, boolean draft, boolean matches) {}
    private record ChoiceHit(int x1, int y1, int x2, int y2, MNode node, RouteView.Choice choice) {}
    private record ActionHit(int x1, int y1, int x2, int y2, String label, Runnable action) {}
    private record SuggestionHit(int x1, int y1, int x2, int y2, Item item) {}
}
