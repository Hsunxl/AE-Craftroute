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
    private static final int MAX_NODES = 80;
    private static final int MAX_QUERIES = 48;

    private final Screen parent;
    private AEKey originalTarget;
    private long originalAmount;
    private boolean fromCrafting;
    private final boolean canModify;
    private RouteWorkbenchPanel workbench;
    private int workbenchTicks,lastWorkbenchSequence;
    private boolean nativeRecipeVisible() {return jeiTab && browser!=null && (selectedRecipe==null || selectedRecipe.draft);}
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
    private RouteLayout uiLayout() {return RouteLayout.of(width,height);}
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
    private boolean jeiTab = true;
    private boolean initialized;
    private RouteRecipeBrowser browser;
    private final Map<String, RouteView.Choice> branches = new LinkedHashMap<>();
    private final Map<String,String> appliedPathChoices=new LinkedHashMap<>();
    private final Map<String, RouteView.Draft> branchDrafts = new LinkedHashMap<>();
    private final Deque<EditState> undo = new ArrayDeque<>();
    private final RouteBranchEdits branchEdits=new RouteBranchEdits();
    private final List<ActionHit> nodeMenuHits=new ArrayList<>();
    private boolean nodeMenu;
    private int nodeMenuX,nodeMenuY,nodeMenuPage,nodeMenuLeft,nodeMenuTop,nodeMenuWidth,nodeMenuHeight;
    private MNode nodeMenuTarget;
    private long ticks;
    private void openNodeMenu(MNode node,double mx,double my) {
        selectedNode=node;selectedKey=node.material.key;selectedRecipe=null;jeiTab=true;
        if(browser!=null)browser.select(node.material.renderKey);
        nodeMenu=true;nodeMenuPage=0;nodeMenuTarget=node;
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
            branchEdits.hide(parent, node.material.key);
            branchEdits.hideMaterial(node.material.key);
            if(slot>=0)branchEdits.hideChild(parent,slot);
            LOGGER.info("Removed material node: path={}, key={}, parent={}, slot={}",node.path,node.material.key,parent,slot);
        } else branchEdits.collapse(node.path);
        branches.keySet().removeIf(p->p.equals(node.path)||p.startsWith(node.path+"/"));
        branchDrafts.keySet().removeIf(p->p.equals(node.path)||p.startsWith(node.path+"/"));
        rebuildGraph();status=wholeNode && node!=root?"已移除节点及其下级；原配方完整材料不变，可撤销":"已移除下级分支，保留物品节点";
    }
    private void drawNodeMenu(GuiGraphics g,int mx,int my) {
        nodeMenuHits.clear();if(!nodeMenu)return;
        var options=browser==null?List.<RouteRecipeBrowser.Option>of():browser.options();
        int rows=Math.min(Math.min(5,Math.max(1,(canvasBottom-canvasTop-94)/18)),options.size()),w=Math.min(240,canvasWidth()-8);
        int h=(rows+4)*18+4;
        int x=Math.max(canvasLeft,Math.min(nodeMenuX,canvasRight-w));
        int y=Math.max(canvasTop,Math.min(nodeMenuY,canvasBottom-h));
        nodeMenuLeft=x;nodeMenuTop=y;nodeMenuWidth=w;nodeMenuHeight=h;
        nodeMenuPage=Math.max(0,Math.min(nodeMenuPage,Math.max(0,options.size()-rows)));
        g.pose().pushPose();g.pose().translate(0,0,1000);
        g.fill(x,y,x+w,y+h,0xFFCBCCD4);frame(g,x,y,w,h,ACCENT);
        text(g,"选择配方 · "+(options.isEmpty()?"无配方":(nodeMenuPage+1)+"–"+Math.min(options.size(),nodeMenuPage+rows)+" / "+options.size()),x+4,y+5,w-8,TEXT);
        for(int row=0;row<rows;row++) {
            int i=nodeMenuPage+row;if(i>=options.size())break;
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
        menuAction(g,x,by,w/2,"‹ 上一页",()->nodeMenuPage=Math.max(0,nodeMenuPage-Math.max(1,rows)));
        menuAction(g,x+w/2,by,w-w/2,"下一页 ›",()->nodeMenuPage=Math.min(Math.max(0,options.size()-rows),nodeMenuPage+Math.max(1,rows)));
        menuAction(g,x,by+18,w,"只清除下级（保留此物品）",()->removeBranch(nodeMenuTarget,false));
        menuAction(g,x,by+36,w,"移除此节点及下级",()->removeBranch(nodeMenuTarget,true));
        g.pose().popPose();
    }
    private void menuAction(GuiGraphics g,int x,int y,int w,String label,Runnable run) {
        g.fill(x+1,y,x+w-1,y+17,0xFFADB0C4);text(g,label,x+4,y+5,w-8,TEXT);
        nodeMenuHits.add(new ActionHit(x,y,x+w,y+18,label,run));
    }
    private void dropRecipeIngredient(RouteView.Draft value,AEKey ingredient,double mx,double my) {
        LOGGER.info("Route canvas ingredient drop: recipe={}, ingredient={}, parent={}",value.recipeId(),ingredient.getDisplayName().getString(),activeMaterial()==null?"none":activeMaterial().material.name);
        if(!inside(mx,my,canvasLeft,canvasTop,canvasWidth(),canvasBottom-canvasTop)
                || workbench!=null && workbench.contains(mx,my)) {status="拖入画布空白处或目标物品节点，才会创建材料分支";return;}
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
            int ox=(int)contentX(mx)-child.autoX-child.w/2,oy=(int)contentY(my)-child.autoY-child.h/2;
            offsets.put("m:"+child.path,new int[]{ox,oy});child.x=child.autoX+ox;child.y=child.autoY+oy;break;
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
    private void checkpoint() { pushUndo(captureState()); }
    private void undoEdit() {
        if (undo.isEmpty()) { status="没有可撤销的画布操作"; return; }
        var state=undo.removeLast();branchEdits.restore(state.edits);nodeMenu=false;
        target=state.target; targetAmount=state.amount; targetKeyId=keyId(target); manualBuild=state.manual;
        branches.clear(); branches.putAll(state.branches); branchDrafts.clear(); branchDrafts.putAll(state.drafts);
        offsets.clear(); state.offsets.forEach((path, offset) -> offsets.put(path, offset.clone()));
        selectedNode=null; selectedRecipe=null; setFieldText(targetName(target)); rebuildGraph(); status="已退回上一步（仅画布，不回收已生成的样板）";
    }
    private void clearManual() {
        checkpoint(); branchEdits.clear();nodeMenu=false;manualBuild=true; branches.clear(); branchDrafts.clear(); draftChoice=null; draft=null;
        offsets.clear(); selectedNode=null; selectedRecipe=null; rebuildGraph(); status="已清空分支，保留最终产物起点";
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
    private void restoreTree() { send("restore",null,""); }
    public int contentRight() { return width <= 0 || height <= 0 ? 320 : uiLayout().contentRight(); }
    public void showRecipes(AEKey key) { jeiTab=true; if(browser!=null)browser.select(key); }
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
    /** Ticks at which the unanswered "begin" is re-sent; -1 means the server already answered. */
    private long beginRetryAt = -1;
    private int beginAttempts;

    private int panelW, panelLeft, panelRight, canvasLeft, canvasRight, toolbarY, canvasTop, canvasBottom;
    private int panX, panY;
    private float zoom = 1.0F;
    private boolean fitPending;
    private int choiceScroll;
    private int queryCount;
    private static int nextSequence;
    private int latestPlanSequence;
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final Map<String, int[]> offsets = new HashMap<>();
    private final List<MNode> materialNodes = new ArrayList<>();
    private final List<RNode> recipeNodes = new ArrayList<>();
    private final List<ChoiceHit> choiceHits = new ArrayList<>();
    private final List<ActionHit> actionHits = new ArrayList<>();
    private final List<SuggestionHit> suggestionHits = new ArrayList<>();
    private List<Item> suggestions = List.of();
    private int suggestionPage;
    private int suggestionX, suggestionY, suggestionW, suggestionH;
    private boolean suppressSuggestions;
    private MNode root;

    private MNode wireFrom;
    private double wireX, wireY;
    private MNode draggedMaterial;
    private RNode draggedRecipe;
    private EditState pendingDragState;
    private double dragMouseX, dragMouseY;
    private int dragNodeX, dragNodeY;
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
        layout();
        if(workbench==null)workbench=new RouteWorkbenchPanel((action,argument)->send(action,null,argument));
        workbench.layout(panelLeft+6,panelRight-panelLeft-14,canvasBottom,canvasLeft,canvasWidth(),canvasTop,compact(),uiLayout().collapseCache());
        buildWidgets();
        status = target == null ? "从合成树节点按 F8，或输入物品名称设置目标" : "正在读取可用配方…";
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
        beginAttempts++;
        if(send("begin",null,"")>0) beginRetryAt=ticks+RouteRequestRules.BEGIN_RETRY_FRAMES;
    }

    /**
     * Retries the target handshake while the canvas is still waiting for one. The server can
     * only report {@code whatToCraft} once the confirmation plan exists, and it says nothing
     * when it does not, so an unanswered attempt must be repeated rather than assumed final.
     */
    private void retryBeginIfDue() {
        if(beginRetryAt<0 || target!=null) {beginRetryAt=-1;return;}
        if(beginAttempts>=RouteRequestRules.BEGIN_MAX_ATTEMPTS) {
            beginRetryAt=-1;
            if(Boolean.getBoolean("aecraftroute.qa"))
                LOGGER.info("Route QA give up authoritative begin target: menu={}, attempts={}",menuId,beginAttempts);
            status="未能从合成确认页读取目标，请输入物品名称手动设置";
            return;
        }
        if(RouteRequestRules.beginRetryDue(ticks,beginRetryAt)) pressBegin();
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
            addRenderableWidget(new AeButton(12, Math.max(52, height - 40), 80, 20,
                    Component.literal("返回"), b -> onClose(), false));
            return;
        }
        int modeX=compact()?54:104;
        var modify=new AeButton(modeX,3,36,16,Component.literal("修改"),b->switchMode(true),fromCrafting);
        modify.active=canModify;addRenderableWidget(modify);
        addRenderableWidget(new AeButton(modeX+40,3,36,16,Component.literal("新建"),b->switchMode(false),!fromCrafting));
        int x=8;
        if(fromCrafting) { addToolbarButton(compact()?"还原":"还原合成树",x,compact()?52:76,"恢复进入时的网络合成树，不回收已生成样板",this::restoreTree); x+=compact()?56:80; }
        addToolbarButton(compact()?"撤销":"退回上一步",x,compact()?52:76,"撤销上一次画布编辑，不回收已经生成的样板",this::undoEdit); x+=compact()?56:80;
        addToolbarButton(compact()?"清空":"清空手动创建",x,compact()?52:84,"清空手动分支，保留最终产物节点",this::clearManual); x+=compact()?56:88;
        addToolbarButton(compact()?"整理":"自动整理",x,compact()?52:76,"清除手动挪动的位置，按树形重新排列并居中；Home 键同效，配方与材料不变",this::autoArrange); x+=compact()?56:80;
        addToolbarButton(compact()?"应用":"整幅应用到合成",x,compact()?52:96,"强制画布程序：每个路径至少执行画布计算批次，可继续循环；共享库存，证明成功后原子应用",this::applyCanvas);

        int fieldW = compact()?Math.max(54,Math.min(120,contentRight()-188)):Math.min(180, Math.max(92, contentRight() / 5));
        int fieldX = contentRight() - 8 - (compact()?38:48) - fieldW;
        int fieldY=compact()?3:22;
        itemId = new EditBox(font, fieldX, fieldY, fieldW, 18, Component.literal("物品名称"));
        itemId.setMaxLength(256);
        itemId.setValue(target == null ? "" : targetName(target));
        if (target != null && !(target instanceof AEItemKey)) itemId.setValue("");
        itemId.setHint(Component.literal(target != null && !(target instanceof AEItemKey)
                ? "流体目标请从合成树或 JEI 打开" : "中文名，或 ae2:... 物品代码"));
        itemId.setResponder(this::onSearchChanged);
        addRenderableWidget(itemId);
        addRenderableWidget(new AeButton(contentRight() - (compact()?42:52), compact()?3:21,
                compact()?34:44, 19, Component.literal(compact()?"目标":"设为目标"), b -> setTargetFromField(), false));
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
        var result = new ArrayList<Item>();
        var byId = ResourceLocation.tryParse(query);
        if (byId != null && BuiltInRegistries.ITEM.containsKey(byId)) {
            result.add(BuiltInRegistries.ITEM.get(byId));
        }
        String lower = query.toLowerCase(Locale.ROOT);
        var partial = new ArrayList<Item>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || result.contains(item)) continue;
            var stack = new ItemStack(item);
            String name = stack.getHoverName().getString();
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (name.equalsIgnoreCase(query) || id.equalsIgnoreCase(query)) {
                result.add(0, item);
            } else if (name.toLowerCase(Locale.ROOT).contains(lower)
                    || id.toLowerCase(Locale.ROOT).contains(lower)) {
                partial.add(item);
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
        target = key;
        targetAmount = Math.max(0, amount);
        this.source = source == null ? "手动" : source;
        this.path = path == null ? "" : path;
        targetKeyId = key == null ? null : keyId(key);
        selectedKey = targetKeyId;
        selectedNode = null;
        selectedRecipe = null;
        branchEdits.clear();nodeMenu=false;branches.clear(); branchDrafts.clear(); manualBuild=true; draftChoice=null; draft=null;
        materials.clear();
        offsets.clear();
        pending.clear();
        queryCount = 0;
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
        send("stock", node.material.renderKey, "");
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
        if (key == null || queryCount >= MAX_QUERIES) return;
        Material material = ensureMaterial(key);
        if (material.reply != null || material.loading || material.error != null) return;
        material.loading = true;
        queryCount++;
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
        if (argument.length()>24000) { status="配方数据过大，无法编码"; return 0; }
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

    public void accept(RouteNetwork.Response response) {
        if (response.menu() != menuId) {
            // Belongs to another menu generation; log it so a mismatched reply is never invisible.
            if(Boolean.getBoolean("aecraftroute.qa"))LOGGER.info("Route QA response for foreign menu: menu={}, response={}, sequence={}",menuId,response.menu(),response.sequence());
            return;
        }
        Pending request = pending.get(response.sequence());
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
            return;
        }
        if (reply == null) return;
        appliedPathChoices.clear();
        if(reply.canvas()!=null && Objects.equals(reply.canvas().target(),keyId(target))
                && reply.canvas().amount()==Math.max(1,targetAmount) && reply.canvas().paths()!=null)
            for(var p:reply.canvas().paths())appliedPathChoices.put(p.path(),p.choiceId());
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
            if(target!=null) beginRetryAt=-1;
            return;
        }
        if(request.action.equals("restore")) {
            if(reply.message()!=null && reply.message().startsWith("操作失败")) {status=reply.message();return;}
            checkpoint(); target=originalTarget;targetAmount=originalAmount;targetKeyId=keyId(target);
            manualBuild=false;branchEdits.clear();nodeMenu=false;branches.clear();branchDrafts.clear();offsets.clear();draftChoice=null;draft=null;
            centerPending=true;autoCenter=true;setFieldText(targetName(target));
        }
        if (request.action.equals("clear") || request.action.equals("restore")) {
            materials.clear();
            pending.clear();
            root = null;
            selectedNode = null;
            selectedRecipe = null;
            queryCount = 0;
            if (target != null) {
                ensureMaterial(target);
                query(target);
            }
            status = reply.message();
            rebuildGraph();
            return;
        }
        if ("preview".equals(request.action)) {
            plan = reply.plan();
            // Remember the answered signature here, not at send time: a lost or stale reply
            // must be retryable, otherwise the authoritative panel stays blank for good.
            lastPreviewSignature = request.signature;
            String summary = planSummary();
            status = summary != null ? summary : reply.message();
            rebuildGraph();
            return;
        }
        if ("apply".equals(request.action)) {
            plan = reply.plan();
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
            status = reply.message() == null ? "就绪" : reply.message();
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
        if (sequence != 0) plan = null;
    }

    /**
     * Submit every recipe chosen on the canvas at once.
     *
     * <p>Nothing is applied silently: the server reports which materials it locked, which
     * picks are stale, and which material needs two different recipes (a per-material lock
     * cannot express that). The reply carries the authoritative plan for the new rules.</p>
     */
    private void applyCanvas() {
        if (!canModify) { status = "请从自动合成确认界面进入修改模式"; return; }
        if (target == null) { status = "先设置最终产物，再整幅应用"; return; }
        var nodes = new com.google.gson.JsonArray();
        int skipped = 0;
        for (var node : materialNodes) {
            var choice = node.preview == null ? null : node.preview.choice();
            if (choice == null || choice.id().startsWith("plan:") || node.material.key == null) { skipped++; continue; }
            var entry = new com.google.gson.JsonObject();
            entry.addProperty("k", node.material.key);
            entry.addProperty("c", choice.id());
            entry.addProperty("n", node.material.name);
            entry.addProperty("p", node.path);
            nodes.add(entry);
        }
        if (nodes.isEmpty()) { status = "画布上还没有可应用的配方；先展开材料并选择样板"; return; }
        var json = new com.google.gson.JsonObject();
        json.addProperty("amount", Math.max(1, targetAmount));
        json.add("nodes", nodes);
        status = "正在证明强制画布：" + nodes.size() + " 个路径分支（每分支至少执行画布批次）"
                + (skipped > 0 ? "（" + skipped + " 个节点没有可应用样板）" : "");
        send("apply", target, json.toString());
    }

    /** Startup capital this plan proves for one material, or null when it needs none. */
    private String provenStartup(String name) {        if (plan == null || !plan.proven() || name == null) return null;
        for (var stack : plan.startup()) {
            if (name.equals(stack.name())) return RouteGraphMath.format(stack.amount());
        }
        return null;
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

    private void rebuildGraph() {
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
        if (root != null && autoCenter) panX = Math.round(canvasWidth() / zoom / 2 - root.subtreeW / 2F);
        if (root != null) applyOffsets(root);
        if(fitPending && root!=null) {
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
            for(var node:materialNodes) {minX=Math.min(minX,node.x);minY=Math.min(minY,node.y);maxX=Math.max(maxX,node.x+node.w);maxY=Math.max(maxY,node.y+node.h);}
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
        if (materialNodes.size() >= MAX_NODES) return null;
        if (depth > 0 && branchEdits.materialHidden(material.key)) {
            LOGGER.info("Skipping hidden material at {}: {}",path,material.key);
            return null;
        }
        MNode node = new MNode(path, material, demand, depth);
        materialNodes.add(node);
        var custom=branches.get(path);
        var planned=originalChoices.get(material.key);
        if(planned!=null && material.reply!=null) {
            for(var candidate:material.reply.choices()) {
                if(amounts(candidate.inputs()).equals(amounts(planned.inputs())) && amounts(candidate.outputs()).equals(amounts(planned.outputs()))) {
                    planned=candidate;break;
                }
            }
        }
        node.preview = custom!=null ? new RouteGraphMath.Preview(custom,false,custom.id().startsWith("jei:"),"手动创建")
                : manualBuild ? new RouteGraphMath.Preview(null,false,false,"选择右侧配方创建分支")
                : planned!=null ? new RouteGraphMath.Preview(planned,false,false,"原始合成计划")
                : !originalChoices.isEmpty() ? new RouteGraphMath.Preview(null,false,false,"系统计划使用库存或原材料")
                : RouteGraphMath.preview(material.reply, draftFor(material.key));
        if(custom==null && appliedPathChoices.containsKey(path)) {
            var id=appliedPathChoices.get(path);RouteView.Choice matched=null;
            if(material.reply!=null) for(var candidate:material.reply.choices())if(id.equals(candidate.id())){matched=candidate;break;}
            node.preview=new RouteGraphMath.Preview(matched,true,false,material.reply==null?"正在读取已应用路径样板…":"已应用的强制路径（缺失时不改用别的配方）");
        }
        node.cycle = ancestors.contains(material.key);
        if (node.cycle) {
            node.preview = new RouteGraphMath.Preview(null, false, false, "回料引用：此处连接上层材料；可行性由服务端库存与执行顺序验证");
            return node;
        }
        if (!branchEdits.expanded(path) || depth >= 24 || node.preview.choice() == null) return node;

        RouteView.Choice choice = node.preview.choice();
        long output = RouteGraphMath.outputAmount(choice, material.key);
        if (output <= 0) {
            node.preview = new RouteGraphMath.Preview(choice, node.preview.locked(),
                    node.preview.draft(), "这张配方不产出这个材料，不能放在这里");
            return node;
        }
        node.recipe = new RNode(node, choice, node.preview.draft());
        recipeNodes.add(node.recipe);

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
            for (int i = 0; i < grouped.size() && materialNodes.size() < MAX_NODES; i++) {
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
                if (child != null) node.children.add(child);
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
        node.h = 62;
        if (node.recipe != null) {
            node.recipe.w = 82;
            node.recipe.h = 20;
            int row = 0;
            for (var child : node.children) {
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
        recipe.autoX = left + (node.subtreeW - recipe.w) / 2;
        recipe.autoY = top + node.h + 16;
        recipe.x = recipe.autoX;
        recipe.y = recipe.autoY;

        int total = 0;
        for (var child : node.children) total += child.subtreeW + (total == 0 ? 0 : 14);
        int childLeft = left + (node.subtreeW - total) / 2;
        int childTop = recipe.y + recipe.h + 18;
        for (var child : node.children) {
            place(child, childLeft, childTop);
            childLeft += child.subtreeW + 14;
        }
    }

    private void applyOffsets(MNode node) {
        int[] offset = offsets.get("m:" + node.path);
        if (offset != null) {
            node.x = node.autoX + offset[0];
            node.y = node.autoY + offset[1];
        }
        if (node.recipe != null) {
            int[] recipeOffset = offsets.get("r:" + node.path);
            if (recipeOffset != null) {
                node.recipe.x = node.recipe.autoX + recipeOffset[0];
                node.recipe.y = node.recipe.autoY + recipeOffset[1];
            }
        }
        for (var child : node.children) applyOffsets(child);
    }

    private void requestMissing() {
        if (queryCount >= MAX_QUERIES) return;
        for (var node : new ArrayList<>(materialNodes)) {
            if (node.material.reply == null && !node.material.loading && node.material.error == null) {
                query(node.material.renderKey);
                if (queryCount >= MAX_QUERIES) break;
            }
        }
    }

    private int screenX(MNode node) { return canvasLeft + Math.round((panX + node.x) * zoom); }
    private int screenY(MNode node) { return canvasTop + 18 + Math.round((panY + node.y) * zoom); }
    private int screenX(RNode node) { return canvasLeft + Math.round((panX + node.x) * zoom); }
    private int screenY(RNode node) { return canvasTop + 18 + Math.round((panY + node.y) * zoom); }
    private double contentX(double mouseX) { return (mouseX - canvasLeft) / zoom - panX; }
    private double contentY(double mouseY) { return (mouseY - canvasTop - 18) / zoom - panY; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        // Keep the NeoForge BackgroundRendered hook alive: JEI draws its own list background there.
        super.renderBackground(g,mx,my,dt);
        appeng.client.gui.style.BackgroundGenerator.draw(contentRight(),height,g,0,0);
        if (!usable()) return;
        g.fill(canvasLeft-2,canvasTop-2,canvasRight+2,canvasBottom+2,CANVAS_EDGE);
        g.fill(canvasLeft,canvasTop,canvasRight,canvasBottom,CANVAS_BG);
        for(int gx=canvasLeft+8;gx<canvasRight;gx+=16)
            for(int gy=canvasTop+24;gy<canvasBottom;gy+=16)
                g.fill(gx,gy,gx+1,gy+1,CANVAS_GRID);
        appeng.client.gui.style.BackgroundGenerator.draw(panelRight-panelLeft+4,canvasBottom-canvasTop+4,g,panelLeft-2,canvasTop-2);
        frame(g,panelLeft-2,canvasTop-2,panelW+4,canvasBottom-canvasTop+4,CANVAS_EDGE);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        // super.render calls renderBackground (which fires BackgroundRendered for JEI) and
        // renders the vanilla widgets. JEI's ingredient list is drawn later in Render.Post,
        // i.e. above this content, so the right-hand strip stays a real JEI panel.
        super.render(g,mx,my,dt);
        if (!usable()) {
            text(g,"游戏窗口过小",14,28,width-28,GOLD);
            text(g,"请扩大窗口；当前逻辑尺寸 "+width+" × "+height,14,42,width-28,TEXT);
            return;
        }
        drawHeader(g);
        drawCanvas(g, mx, my);
        drawInspector(g, mx, my);
        g.flush();g.pose().pushPose();g.pose().translate(0,0,600);
        workbench.render(g,mx,my);
        g.flush();g.pose().popPose();
        drawFooter(g);
        if(browser!=null && browser.draggingIngredient()) {wireX=mx;wireY=my;}
        drawWire(g);
        drawSuggestions(g, mx, my);
        drawNodeTooltip(g,mx,my);
        workbench.renderDrag(g);
        drawNodeMenu(g,mx,my);
        if(browser!=null)browser.renderDrag(g);
    }

    private void drawHeader(GuiGraphics g) {
        text(g,compact()?"合成树":"合成树画布",compact()?8:10,7,compact()?44:88,TEXT);
        if(compact())return;
        String exit = "Esc 返回";
        text(g, exit, contentRight() - 10 - font.width(exit), 7, font.width(exit) + 2, FAINT);
        if (target == null) {
            text(g, "未设置生产目标", 12, 26, width - 40, GOLD);
            text(g, "输入物品的中文名或物品代码，也可以从合成树节点按 F8，或把 JEI 里的物品拖进来", 12, 37,
                    Math.max(80, (itemId == null ? width : itemId.getX()) - 52), MUTED);
            if (itemId != null) {
                g.fill(itemId.getX() - 20, 23, itemId.getX() - 4, 39, 0xFFB8B8B8);
                frame(g, itemId.getX() - 20, 23, 16, 16, BORDER);
                text(g, "?", itemId.getX() - 15, 27, 12, MUTED);
            }
            return;
        }
        icon(g, target, 14, 26);
        int textX = 34;
        int max = Math.max(70, (itemId == null ? width - 120 : itemId.getX()) - 62);
        String goal = "目标  " + target.getDisplayName().getString();
        text(g, goal, textX, 24, max, GOLD);
        String amount = targetAmount > 0 ? RouteGraphMath.format(targetAmount) : "未指定";
        String amountLabel = source != null && source.startsWith("JEI") ? "单次产出" : "需求";
        String line = amountLabel + " " + amount + "  ·  来源 " + source;
        if (!path.isBlank()) line += "  ·  " + path;
        text(g, line, textX, 35, max, MUTED);
        if (itemId != null && target instanceof AEItemKey) {
            icon(g, target, itemId.getX() - 20, 23);
        }
    }

    private void drawCanvas(GuiGraphics g, int mx, int my) {
        int right = canvasRight - 4;
        text(g, "画布", canvasLeft + 4, canvasTop + 3, 40, TEXT);
        String legend = "材料 ● 配方 ● 手动展开 · Ctrl+滚轮缩放 " + Math.round(zoom * 100) + "%";
        text(g, legend, right - Math.min(330, font.width(legend)), canvasTop + 3,
                Math.min(330, right - canvasLeft - 44), FAINT);
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
        for (var node : materialNodes) drawMaterial(g, node, mx, my);
        for (var recipe : recipeNodes) drawRecipe(g, recipe, mx, my);
        g.pose().popPose();
        g.disableScissor();
    }

    private void drawEdges(GuiGraphics g, MNode node) {
        if (node.recipe != null) {
            int x1 = node.x + node.w / 2;
            int y1 = node.y + node.h;
            int x2 = node.recipe.x + node.recipe.w / 2;
            int y2 = node.recipe.y;
            int color = edgeColor(node);
            drawEdge(g, x1, y1, x2, y2, color, node.preview.locked() || node.preview.draft());
            for (var child : node.children) {
                drawEdge(g, node.recipe.x + node.recipe.w / 2, node.recipe.y + node.recipe.h,
                        child.x + child.w / 2, child.y, color,
                        node.preview.locked() || node.preview.draft());
            }
        }
        for (var child : node.children) drawEdges(g, child);
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
        int border=node.cycle?ACCENT:selected?ACCENT:node==root?GOLD:BORDER;
        g.fill(x,y,x+node.w,y+node.h,selected?0xFFC6C6D4:hover?0xFFD8D9E0:0xFFCBCCD4);
        bevel(g,x,y,node.w,node.h); frame(g,x,y,node.w,node.h,border);
        int slotX=x+(node.w-28)/2,slotY=y+4;
        g.pose().pushPose();g.pose().translate(slotX,slotY,0);g.pose().scale(1.5F,1.5F,1);
        appeng.client.gui.Icon.SLOT_BACKGROUND.getBlitter().dest(0,0).blit(g);
        icon(g,node.material.renderKey,1,1);
        g.pose().popPose();
        // Quantity is a real corner overlay, not a distant text-column field.
        String count=RouteGraphMath.format(node.demand);
        float countScale=0.75F;
        int countWidth=(int)Math.ceil(font.width(count)*countScale);
        int countX=slotX+28-countWidth, countY=slotY+21;
        g.fill(countX-1,countY-1,slotX+29,countY+7,0xC02B2B2B);
        g.pose().pushPose();g.pose().translate(countX,countY,30);g.pose().scale(countScale,countScale,1);
        g.drawString(font,count,0,0,0xFFFFFFFF,true);g.pose().popPose();
        String name=font.plainSubstrByWidth(node.material.name,node.w-8);
        if(!name.equals(node.material.name))name=font.plainSubstrByWidth(node.material.name,node.w-14)+"…";
        text(g,name,x+(node.w-font.width(name))/2,y+37,node.w-6,TEXT);
        String state=node.cycle?"回料引用":node==root?"最终产物":node.recipe==null?"＋ 选择配方":"已展开";
        String startup=provenStartup(node.material.name);
        if(startup!=null) state=state+" · 起手 "+startup;
        text(g,state,x+(node.w-font.width(state))/2,y+50,node.w-6,node.cycle?ACCENT:MUTED);
        int portX=x+node.w-16,portY=y+4;
        g.fill(portX,portY,portX+12,portY+12,0xFFE4DFEB);
        frame(g,portX,portY,12,12,border);text(g,"+",portX+3,portY+2,10,GOLD);
    }

    private long forcedBatches(RNode recipe) {return RouteGraphMath.runs(Math.max(1,recipe.parent.demand),RouteGraphMath.outputAmount(recipe.choice,recipe.parent.material.key));}
    private void drawRecipe(GuiGraphics g,RNode recipe,int mx,int my) {
        int sx=screenX(recipe),sy=screenY(recipe);
        int sw=Math.round(recipe.w*zoom),sh=Math.round(recipe.h*zoom);
        if(sx+sw<canvasLeft || sx>canvasRight || sy+sh<canvasTop || sy>canvasBottom)return;
        boolean selected=selectedRecipe==recipe;
        int x=recipe.x,y=recipe.y;
        g.fill(x,y,x+recipe.w,y+recipe.h,selected?0xFFC6C6D4:0xFFADB0C4);
        bevel(g,x,y,recipe.w,recipe.h);
        frame(g,x,y,recipe.w,recipe.h,selected?ACCENT:BORDER);
        var value=branchDrafts.get(recipe.parent.path);
        if(browser!=null && value!=null)browser.renderMachine(value.recipeId(),g,x+2,y+1,(int)contentX(mx),(int)contentY(my));
        String label=RouteEmcRules.isEmc(value)?"EMC "+RouteEmcRules.cost(RouteEmcRules.unit(value),recipe.parent.demand):
                recipe.draft?"待编码":"应用 ≥"+forcedBatches(recipe)+" 批";
        text(g,label,x+23,y+6,recipe.w-25,TEXT);
    }

    private void drawNodeTooltip(GuiGraphics g,int mx,int my) {
        if(nodeMenu || browser!=null && browser.draggingIngredient() || workbench!=null && workbench.contains(mx,my))return;
        var recipe=recipeAt(mx,my);
        if(recipe!=null) {
            g.renderComponentTooltip(font,List.of(Component.literal("路径："+recipe.parent.path),
                    Component.literal(recipe.draft?"JEI 草稿尚未编码":"应用后此分支至少执行 "+forcedBatches(recipe)+" 批；允许继续循环增产，库存共享"),
                    Component.literal("左键拖动机器节点；单击或右键选择配方")),mx,my);
            return;
        }
        var node=materialAt(mx,my);
        if(node==null)return;
        var lines=new ArrayList<Component>();
        lines.add(Component.literal(node.material.name));
        lines.add(Component.literal("需求："+node.demand+(node.material.renderKey instanceof AEFluidKey?" mB":" 个")));
        if (plan != null) {
            String startup = provenStartup(node.material.name);
            lines.add(Component.literal(plan.proven() ? "权威校验：已证明可行" : "权威校验：" + plan.message()));
            if (startup != null) lines.add(Component.literal("起手只需要：" + startup));
        }
        lines.add(Component.literal("点击：右侧预览配方；＋ / 右键：直接选配方、创建或删除分支"));
        if(node.recipeError!=null)lines.add(Component.literal(node.recipeError));
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
            else { browser.select(node.material.renderKey);browser.demand(node.demand); browser.render(g,x,uiLayout().recipeTop(),max,uiLayout().recipeHeight(),mx,my); }
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
        text(g, "输入原料", x, canvasTop + 58, max, MUTED);
        int y = canvasTop + 71;
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
        if(materialNodes.size()>=MAX_NODES)line="已达 80 节点显示上限；"+line;
        text(g, line, 10, height - 18, contentRight() - 20, color);
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
        if (x + w > width - 8) x = width - 8 - w;
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
        if(nodeMenu) {
            for(var hit:nodeMenuHits)if(inside(mx,my,hit.x1,hit.y1,hit.x2-hit.x1,hit.y2-hit.y1)) {if(button==0)hit.action.run();return true;}
            if(inside(mx,my,nodeMenuLeft,nodeMenuTop,nodeMenuWidth,nodeMenuHeight))return true;
            nodeMenu=false; // Close without swallowing a drag beginning outside this popup.
        }
        if (super.mouseClicked(mx, my, button)) {
            if (itemId != null && itemId.isFocused()) onSearchChanged(itemId.getValue());
            return true;
        }
        if(mx>=contentRight())return false;
        if(workbench!=null && workbench.click(mx,my,button))return true;
        if (button != 0 && button != 1) return false;

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
        RNode recipe = recipeAt(mx, my);
        if (material != null) {
            jeiTab=true;
            choiceScroll=0;
            selectedNode = material;
            selectedKey = material.material.key;
            selectedRecipe = null;
            if (button==1 || recipePortHit(material, mx, my) || contentY(my)>=material.y+47) {
                openNodeMenu(material,mx,my);
            } else {
                draggedMaterial = material;
                pendingDragState = captureState();
                dragMouseX = mx;
                dragMouseY = my;
                dragNodeX = material.x;
                dragNodeY = material.y;
                setDragging(true);
            }
            return true;
        }
        if (recipe != null) {
            var value=branchDrafts.get(recipe.parent.path);
            if(button==1) {openNodeMenu(recipe.parent,mx,my);return true;}
            selectedNode = recipe.parent;
            selectedKey = recipe.parent.material.key;
            selectedRecipe = recipe;
            draggedRecipe = recipe;
            pendingDragState = captureState();
            dragMouseX = mx;
            dragMouseY = my;
            dragNodeX = recipe.x;
            dragNodeY = recipe.y;
            setDragging(true);
            return true;
        }
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
        if (!inside(mx, my, canvasLeft, canvasTop + 16, canvasWidth(), canvasBottom - canvasTop - 16)) return null;
        double x = contentX(mx), y = contentY(my);
        for (int i = materialNodes.size() - 1; i >= 0; i--) {
            var node = materialNodes.get(i);
            if (inside(x, y, node.x, node.y, node.w, node.h)) return node;
        }
        return null;
    }

    private RNode recipeAt(double mx, double my) {
        if (!inside(mx, my, canvasLeft, canvasTop + 16, canvasWidth(), canvasBottom - canvasTop - 16)) return null;
        double x = contentX(mx), y = contentY(my);
        for (int i = recipeNodes.size() - 1; i >= 0; i--) {
            var node = recipeNodes.get(i);
            if (inside(x, y, node.x, node.y, node.w, node.h)) return node;
        }
        return null;
    }

    private boolean recipePortHit(MNode node, double mx, double my) {
        double x = contentX(mx);
        double y = contentY(my);
        double portX = node.x + node.w - 16;
        double portY = node.y + 4;
        return x >= portX && x < portX + 12 && y >= portY && y < portY + 12;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if(workbench!=null && workbench.drag(mx,my))return true;
        if(draggedMaterial==null && draggedRecipe==null && !panning && nativeRecipeVisible() && browser.drag(mx,my,button,dx,dy))return true;
        if (wireFrom != null) {
            wireX = mx;
            wireY = my;
            return true;
        }
        if (draggedMaterial != null) {
            draggedMaterial.x = dragNodeX + (int) Math.round((mx - dragMouseX) / zoom);
            draggedMaterial.y = dragNodeY + (int) Math.round((my - dragMouseY) / zoom);
            return true;
        }
        if (draggedRecipe != null) {
            draggedRecipe.x = dragNodeX + (int) Math.round((mx - dragMouseX) / zoom);
            draggedRecipe.y = dragNodeY + (int) Math.round((my - dragMouseY) / zoom);
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
        if(workbench!=null && workbench.release(mx,my))return true;
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
            if (draggedMaterial.x != dragNodeX || draggedMaterial.y != dragNodeY) pushUndo(pendingDragState);
            int ox = draggedMaterial.x - draggedMaterial.autoX;
            int oy = draggedMaterial.y - draggedMaterial.autoY;
            offsets.put("m:" + draggedMaterial.path, new int[]{ox, oy});
            draggedMaterial = null;
            pendingDragState = null;
            return true;
        }
        if (draggedRecipe != null) {
            if(Math.hypot(mx-dragMouseX,my-dragMouseY)<4) {
                var value=branchDrafts.get(draggedRecipe.parent.path);
                if(value!=null && browser!=null && contentX(dragMouseX)<draggedRecipe.x+21)browser.showMachine(value.recipeId());
                else openNodeMenu(draggedRecipe.parent,mx,my);
                draggedRecipe=null;
                pendingDragState=null;
                return true;
            }
            if (draggedRecipe.x != dragNodeX || draggedRecipe.y != dragNodeY) pushUndo(pendingDragState);
            int ox = draggedRecipe.x - draggedRecipe.autoX;
            int oy = draggedRecipe.y - draggedRecipe.autoY;
            offsets.put("r:" + draggedRecipe.parent.path, new int[]{ox, oy});
            draggedRecipe = null;
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
        if (suggestionsVisible() && inside(mx,my,suggestionX,suggestionY,suggestionW,suggestionH)) {
            if (scrollY != 0) suggestionPage = Math.max(0, Math.min(suggestionPages()-1,
                    suggestionPage + (scrollY < 0 ? 1 : -1)));
            return true;
        }
        if(nodeMenu) {if(scrollY!=0)nodeMenuPage+=scrollY<0?1:-1;return true;}
        if(workbench!=null && workbench.scroll(mx,my,scrollY))return true;
        if (scrollY==0)return false;
        if (inside(mx, my, panelLeft, canvasTop, panelW, canvasBottom - canvasTop)) {
            if(nativeRecipeVisible()) {
                if(!browser.scroll(mx,my,scrollX,scrollY))browser.page(scrollY<0?1:-1);return true;
            }
            choiceScroll += scrollY < 0 ? 1 : -1;
            return true;
        }
        if (inside(mx, my, canvasLeft, canvasTop, canvasWidth(), canvasBottom - canvasTop)) {
            autoCenter = false;
            if (hasControlDown()) {
                double cx = contentX(mx), cy = contentY(my);
                float next = zoom + (scrollY > 0 ? 0.1F : -0.1F);
                zoom = Math.max(0.25F, Math.min(2.0F, next));
                panX = (int) Math.round((mx - canvasLeft) / zoom - cx);
                panY = (int) Math.round((my - canvasTop - 18) / zoom - cy);
            } else {
                panY += scrollY > 0 ? 18 : -18;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(itemId==null || !itemId.isFocused()) {
            if(nativeRecipeVisible() && (workbench==null || !workbench.hovered()) && browser.key(keyCode,scanCode))return true;
            if(keyCode==GLFW.GLFW_KEY_ESCAPE && nodeMenu) {nodeMenu=false;return true;}
            if(keyCode==GLFW.GLFW_KEY_DELETE) {removeBranch(true);return true;}
            if(keyCode==GLFW.GLFW_KEY_Z && hasControlDown()) {undoEdit();return true;}
            if(keyCode==GLFW.GLFW_KEY_HOME) {autoArrange();return true;}
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && itemId != null && itemId.isFocused()) {
            setTargetFromField();
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
        Minecraft.getInstance().setScreen(parent);
    }

    /** Area where JEI can drop an item/fluid to make it the target. */
    public net.minecraft.client.renderer.Rect2i getDropArea() {
        return new net.minecraft.client.renderer.Rect2i(canvasLeft, canvasTop,
                Math.max(1, canvasWidth()), Math.max(1, (workbench==null?canvasBottom:workbench.dropBottom()) - canvasTop));
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
        var expired=new ArrayList<Pending>();
        pending.entrySet().removeIf(e->{if(RouteRequestRules.expired(ticks,e.getValue().started)) {expired.add(e.getValue());return true;}return false;});
        for(var request:expired) {
            if(request.key!=null) {var m=materials.get(keyId(request.key));if(m!=null)m.loading=false;}
            if("preview".equals(request.action) || "apply".equals(request.action)) {
                // No verdict arrived. Release the dedupe gate so the next rebuild asks again
                // instead of leaving the authoritative panel blank for this target and amount.
                lastPreviewSignature=null;
                status="服务器未在 10 秒内确认规划；将重新询问，不会自动应用";
            } else if("workbench".equals(request.action)) {
                status="样板缓存刷新未收到服务器回应，稍后自动重试";
            } else if(RouteRequestRules.mutation(request.action))status="服务器未在 10 秒内确认；未自动重试，请检查缓存后再操作";
        }
        if(browser!=null)browser.tick();
        retryBeginIfDue();
        if(workbench!=null && ++workbenchTicks%40==0)workbench.refresh();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu.containerId != menuId) {
            mc.setScreen(null);
        }
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

    private Map<AEKey, Long> amounts(List<RouteView.Stack> stacks) {
        var result = new HashMap<AEKey, Long>();
        for (var stack : stacks) {
            result.merge(stack.decode(Minecraft.getInstance().level.registryAccess()),
                    stack.amount(), Math::addExact);
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
        boolean cycle;
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
