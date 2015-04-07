package mekanism.common.multipart;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import mekanism.api.Coord4D;
import mekanism.api.EnumColor;
import mekanism.api.Range4D;
import mekanism.api.transmitters.IGridTransmitter;
import mekanism.api.transmitters.TransmissionType;
import mekanism.client.render.RenderPartTransmitter;
import mekanism.common.HashList;
import mekanism.common.InventoryNetwork;
import mekanism.common.Mekanism;
import mekanism.common.Tier;
import mekanism.common.Tier.TransporterTier;
import mekanism.common.base.ILogisticalTransporter;
import mekanism.common.content.transporter.InvStack;
import mekanism.common.content.transporter.PathfinderCache;
import mekanism.common.content.transporter.TransporterManager;
import mekanism.common.content.transporter.TransporterStack;
import mekanism.common.content.transporter.TransporterStack.Path;
import mekanism.common.network.PacketDataRequest.DataRequestMessage;
import mekanism.common.network.PacketTileEntity.TileEntityMessage;
import mekanism.common.tile.TileEntityLogisticalSorter;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.TransporterUtils;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.common.util.ForgeDirection;
import codechicken.lib.vec.Vector3;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class PartLogisticalTransporter extends PartTransmitter<InventoryNetwork> implements ILogisticalTransporter
{
	public Tier.TransporterTier tier = Tier.TransporterTier.BASIC;
	
	public static TransmitterIcons transporterIcons = new TransmitterIcons(8, 16);

	public int speed = 5;

	public EnumColor color;

	public int pullDelay = 0;

	public HashList<TransporterStack> transit = new HashList<TransporterStack>();

	public Set<TransporterStack> needsSync = new HashSet<TransporterStack>();
	
	public PartLogisticalTransporter(Tier.TransporterTier transporterTier)
	{
		tier = transporterTier;
	}
	
	protected PartLogisticalTransporter() {}

	@Override
	public String getType()
	{
		return "mekanism:logistical_transporter_" + tier.name().toLowerCase();
	}

	@Override
	public TransmitterType getTransmitter()
	{
		return tier.type;
	}

	@Override
	public TransmissionType getTransmissionType()
	{
		return TransmissionType.ITEM;
	}

	public static void registerIcons(IIconRegister register)
	{
		transporterIcons.registerCenterIcons(register, new String[] {"LogisticalTransporterBasic", "LogisticalTransporterAdvanced", "LogisticalTransporterElite", "LogisticalTransporterUltimate", "RestrictiveTransporter", 
				"DiversionTransporter", "LogisticalTransporterGlass", "LogisticalTransporterGlassColored"});
		transporterIcons.registerSideIcons(register, new String[] {"LogisticalTransporterVerticalBasic", "LogisticalTransporterVerticalAdvanced", "LogisticalTransporterVerticalElite", "LogisticalTransporterVerticalUltimate", 
				"LogisticalTransporterHorizontalBasic", "LogisticalTransporterHorizontalAdvanced", "LogisticalTransporterHorizontalElite", "LogisticalTransporterHorizontalUltimate", "RestrictiveTransporterVertical", 
				"RestrictiveTransporterHorizontal", "LogisticalTransporterVerticalGlass", "LogisticalTransporterVerticalGlassColored", "LogisticalTransporterHorizontalGlass", "LogisticalTransporterHorizontalGlassColored",
				"DiversionTransporterVertical", "DiversionTransporterHorizontal"});
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void renderDynamic(Vector3 pos, float f, int pass)
	{
		if(pass == 0)
		{
			RenderPartTransmitter.getInstance().renderContents(this, f, pos);
		}
	}
	
	@Override
	public void onWorldSeparate()
	{
		super.onWorldSeparate();
		
		if(!world().isRemote)
		{
			PathfinderCache.onChanged(Coord4D.get(tile()));
		}
	}
	
	@Override
	protected boolean isValidTransmitter(TileEntity tileEntity)
	{
		ILogisticalTransporter transporter = (ILogisticalTransporter)tileEntity;

		if(getColor() == null || transporter.getColor() == null || getColor() == transporter.getColor())
		{
			return super.isValidTransmitter(tileEntity);
		}
		
		return false;
	}

	@Override
	public IIcon getCenterIcon(boolean opaque)
	{
		return transporterIcons.getCenterIcon(opaque ? tier.ordinal() : (color != null ? 7 : 6));
	}

	@Override
	public IIcon getSideIcon(boolean opaque)
	{
		return transporterIcons.getSideIcon(opaque ? tier.ordinal() : (color != null ? 11 : 10));
	}

	@Override
	public IIcon getSideIconRotated(boolean opaque)
	{
		return transporterIcons.getSideIcon(opaque ? 4+tier.ordinal() : (color != null ? 13 : 12));
	}

	@Override
	public boolean isValidAcceptor(TileEntity tile, ForgeDirection side)
	{
		return TransporterUtils.isValidAcceptorOnSide(tile, side);
	}
	
	@Override
	public boolean handlesRedstone()
	{
		return false;
	}

	@Override
	public void update()
	{
		super.update();

		if(world().isRemote)
		{
			for(TransporterStack stack : transit)
			{
				if(stack != null)
				{
					stack.progress = Math.min(100, stack.progress+tier.speed);
				}
			}
		}
		else {
			Set<TransporterStack> remove = new HashSet<TransporterStack>();

			pullItems();

			for(TransporterStack stack : transit)
			{
				if(!stack.initiatedPath)
				{
					if(stack.itemStack == null || !recalculate(stack, null))
					{
						remove.add(stack);
						continue;
					}
				}

				stack.progress += tier.speed;

				if(stack.progress > 100)
				{
					Coord4D prevSet = null;

					if(stack.hasPath())
					{
						int currentIndex = stack.pathToTarget.indexOf(Coord4D.get(tile()));
						
						if(currentIndex == 0) //Necessary for transition reasons, not sure why
						{
							remove.add(stack);
							continue;
						}
						
						Coord4D next = stack.pathToTarget.get(currentIndex-1);

						if(!stack.isFinal(this))
						{
							if(next != null && stack.canInsertToTransporter(stack.getNext(this).getTileEntity(world()), ForgeDirection.getOrientation(stack.getSide(this))))
							{
								ILogisticalTransporter nextTile = (ILogisticalTransporter)next.getTileEntity(world());
								nextTile.entityEntering(stack, stack.progress%100);
								remove.add(stack);

								continue;
							}
							else if(next != null)
							{
								prevSet = next;
							}
						}
						else {
							if(stack.pathType != Path.NONE)
							{
								if(next != null && next.getTileEntity(world()) instanceof IInventory)
								{
									needsSync.add(stack);
									IInventory inventory = (IInventory)next.getTileEntity(world());

									if(inventory != null)
									{
										ItemStack rejected = InventoryUtils.putStackInInventory(inventory, stack.itemStack, stack.getSide(this), stack.pathType == Path.HOME);

										if(rejected == null)
										{
											TransporterManager.remove(stack);
											remove.add(stack);
											continue;
										}
										else {
											needsSync.add(stack);
											stack.itemStack = rejected;

											prevSet = next;
										}
									}
								}
							}
						}
					}

					if(!recalculate(stack, prevSet))
					{
						remove.add(stack);
						continue;
					}
					else {
						if(prevSet != null)
						{
							stack.progress = 0;
						}
						else {
							stack.progress = 50;
						}
					}
				}
				else if(stack.progress == 50)
				{
					if(stack.isFinal(this))
					{
						if(stack.pathType == Path.DEST && (!checkSideForInsert(stack) || !InventoryUtils.canInsert(stack.getDest().getTileEntity(world()), stack.color, stack.itemStack, stack.getSide(this), false)))
						{
							if(!recalculate(stack, null))
							{
								remove.add(stack);
								continue;
							}
						}
						else if(stack.pathType == Path.HOME && (!checkSideForInsert(stack) || !InventoryUtils.canInsert(stack.getDest().getTileEntity(world()), stack.color, stack.itemStack, stack.getSide(this), true)))
						{
							if(!recalculate(stack, null))
							{
								remove.add(stack);
								continue;
							}
						}
						else if(stack.pathType == Path.NONE)
						{
							if(!recalculate(stack, null))
							{
								remove.add(stack);
								continue;
							}
						}
					}
					else {
						TileEntity next = stack.getNext(this).getTileEntity(world());
						boolean recalculate = false;

						if(!stack.canInsertToTransporter(next, ForgeDirection.getOrientation(stack.getSide(this))))
						{
							recalculate = true;
						}

						if(recalculate)
						{
							if(!recalculate(stack, null))
							{
								remove.add(stack);
								continue;
							}
						}
					}
				}
			}

			for(TransporterStack stack : remove)
			{
				Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(tile()), getSyncPacket(stack, true)), new Range4D(Coord4D.get(tile())));
				transit.remove(stack);
				MekanismUtils.saveChunk(tile());
			}

			for(TransporterStack stack : needsSync)
			{
				if(transit.contains(stack))
				{
					Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(tile()), getSyncPacket(stack, false)), new Range4D(Coord4D.get(tile())));
				}
			}

			needsSync.clear();
		}
	}

	private boolean checkSideForInsert(TransporterStack stack)
	{
		ForgeDirection side = ForgeDirection.getOrientation(stack.getSide(this));

		return getConnectionType(side) == ConnectionType.NORMAL || getConnectionType(side) == ConnectionType.PUSH;
	}

	private void pullItems()
	{
		if(pullDelay == 0)
		{
			boolean did = false;

			for(ForgeDirection side : getConnections(ConnectionType.PULL))
			{
				TileEntity tile = Coord4D.get(tile()).getFromSide(side).getTileEntity(world());

				if(tile instanceof IInventory)
				{
					IInventory inv = (IInventory)tile;
					InvStack stack = InventoryUtils.takeTopItem(inv, side.ordinal(), tier.pullAmount);

					if(stack != null && stack.getStack() != null)
					{
						ItemStack rejects = TransporterUtils.insert(tile, this, stack.getStack(), color, true, 0);

						if(TransporterManager.didEmit(stack.getStack(), rejects))
						{
							did = true;
							stack.use(TransporterManager.getToUse(stack.getStack(), rejects).stackSize);
						}
					}
				}
			}

			if(did)
			{
				pullDelay = 10;
			}
		}
		else {
			pullDelay--;
		}
	}

	private boolean recalculate(TransporterStack stack, Coord4D from)
	{
		needsSync.add(stack);

		if(stack.pathType != Path.NONE)
		{
			if(!TransporterManager.didEmit(stack.itemStack, stack.recalculatePath(this, 0)))
			{
				if(!stack.calculateIdle(this))
				{
					TransporterUtils.drop(this, stack);
					return false;
				}
			}
		}
		else {
			if(!stack.calculateIdle(this))
			{
				TransporterUtils.drop(this, stack);
				return false;
			}
		}

		if(from != null)
		{
			stack.originalLocation = from;
		}

		return true;
	}

	@Override
	public ItemStack insert(Coord4D original, ItemStack itemStack, EnumColor color, boolean doEmit, int min)
	{
		return insert_do(original, itemStack, color, doEmit, min, false);
	}

	private ItemStack insert_do(Coord4D original, ItemStack itemStack, EnumColor color, boolean doEmit, int min, boolean force)
	{
		ForgeDirection from = Coord4D.get(tile()).sideDifference(original).getOpposite();

		TransporterStack stack = new TransporterStack();
		stack.itemStack = itemStack;
		stack.originalLocation = original;
		stack.homeLocation = original;
		stack.color = color;
		
		if((force && !canReceiveFrom(original.getTileEntity(world()), from)) || !stack.canInsertToTransporter(tile(), from))
		{
			return itemStack;
		}

		ItemStack rejected = stack.recalculatePath(this, min);

		if(TransporterManager.didEmit(stack.itemStack, rejected))
		{
			stack.itemStack = TransporterManager.getToUse(stack.itemStack, rejected);

			if(doEmit)
			{
				transit.add(stack);
				TransporterManager.add(stack);
				Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(tile()), getSyncPacket(stack, false)), new Range4D(Coord4D.get(tile())));
				MekanismUtils.saveChunk(tile());
			}

			return rejected;
		}

		return itemStack;
	}

	@Override
	public ItemStack insertRR(TileEntityLogisticalSorter outputter, ItemStack itemStack, EnumColor color, boolean doEmit, int min)
	{
		ForgeDirection from = Coord4D.get(tile()).sideDifference(Coord4D.get(outputter)).getOpposite();

		TransporterStack stack = new TransporterStack();
		stack.itemStack = itemStack;
		stack.originalLocation = Coord4D.get(outputter);
		stack.homeLocation = Coord4D.get(outputter);
		stack.color = color;

		if(!canReceiveFrom(outputter, from) || !stack.canInsertToTransporter(tile(), from))
		{
			return itemStack;
		}

		ItemStack rejected = stack.recalculateRRPath(outputter, this, min);

		if(TransporterManager.didEmit(stack.itemStack, rejected))
		{
			stack.itemStack = TransporterManager.getToUse(stack.itemStack, rejected);

			if(doEmit)
			{
				transit.add(stack);
				TransporterManager.add(stack);
				Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(tile()), getSyncPacket(stack, false)), new Range4D(Coord4D.get(tile())));
				MekanismUtils.saveChunk(tile());
			}

			return rejected;
		}

		return itemStack;
	}

	@Override
	public void entityEntering(TransporterStack stack, int progress)
	{
		stack.progress = progress;
		transit.add(stack);
		Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(tile()), getSyncPacket(stack, false)), new Range4D(Coord4D.get(tile())));
		MekanismUtils.saveChunk(tile());
	}

	@Override
	public void onWorldJoin()
	{
		super.onWorldJoin();

		if(world().isRemote)
		{
			Mekanism.packetHandler.sendToServer(new DataRequestMessage(Coord4D.get(tile())));
		}
		else {
			PathfinderCache.onChanged(Coord4D.get(tile()));
		}
	}

	@Override
	public void handlePacketData(ByteBuf dataStream) throws Exception
	{
		super.handlePacketData(dataStream);
		
		int type = dataStream.readInt();

		if(type == 0)
		{
			int c = dataStream.readInt();

			EnumColor prev = color;

			if(c != -1)
			{
				color = TransporterUtils.colors.get(c);
			}
			else {
				color = null;
			}

			if(prev != color)
			{
				tile().markRender();
			}

			transit.clear();

			int amount = dataStream.readInt();

			for(int i = 0; i < amount; i++)
			{
				transit.add(TransporterStack.readFromPacket(dataStream));
			}
		}
		else if(type == 1)
		{
			boolean kill = dataStream.readBoolean();
			int index = dataStream.readInt();

			if(kill)
			{
				transit.remove(index);
			}
			else {
				TransporterStack stack = TransporterStack.readFromPacket(dataStream);

				if(stack.progress == 0)
				{
					stack.progress = 5;
				}

				transit.replace(index, stack);
			}
		}
	}

	@Override
	public ArrayList getNetworkedData(ArrayList data)
	{
		super.getNetworkedData(data);
		
		data.add(0);

		if(color != null)
		{
			data.add(TransporterUtils.colors.indexOf(color));
		}
		else {
			data.add(-1);
		}

		data.add(transit.size());

		for(TransporterStack stack : transit)
		{
			stack.write(this, data);
		}

		return data;
	}

	public ArrayList getSyncPacket(TransporterStack stack, boolean kill)
	{
		ArrayList data = new ArrayList();

		data.add(1);
		data.add(kill);
		data.add(transit.indexOf(stack));

		if(!kill)
		{
			stack.write(this, data);
		}

		return data;
	}

	@Override
	public void load(NBTTagCompound nbtTags)
	{
		super.load(nbtTags);
		
		tier = TransporterTier.values()[nbtTags.getInteger("tier")];

		if(nbtTags.hasKey("color"))
		{
			color = TransporterUtils.colors.get(nbtTags.getInteger("color"));
		}

		if(nbtTags.hasKey("stacks"))
		{
			NBTTagList tagList = nbtTags.getTagList("stacks", NBT.TAG_COMPOUND);

			for(int i = 0; i < tagList.tagCount(); i++)
			{
				TransporterStack stack = TransporterStack.readFromNBT((NBTTagCompound)tagList.getCompoundTagAt(i));

				transit.add(stack);
				TransporterManager.add(stack);
			}
		}
	}

	@Override
	public void save(NBTTagCompound nbtTags)
	{
		super.save(nbtTags);
		
		nbtTags.setInteger("tier", tier.ordinal());

		if(color != null)
		{
			nbtTags.setInteger("color", TransporterUtils.colors.indexOf(color));
		}

		NBTTagList stacks = new NBTTagList();

		for(TransporterStack stack : transit)
		{
			NBTTagCompound tagCompound = new NBTTagCompound();
			stack.write(tagCompound);
			stacks.appendTag(tagCompound);
		}

		if(stacks.tagCount() != 0)
		{
			nbtTags.setTag("stacks", stacks);
		}
	}

	@Override
	protected boolean onConfigure(EntityPlayer player, int part, int side)
	{
		TransporterUtils.incrementColor(this);
		refreshConnections();
		notifyTileChange();
		PathfinderCache.onChanged(Coord4D.get(tile()));
		Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(tile()), getNetworkedData(new ArrayList())), new Range4D(Coord4D.get(tile())));
		player.addChatMessage(new ChatComponentText(EnumColor.DARK_BLUE + "[Mekanism]" + EnumColor.GREY + " " + MekanismUtils.localize("tooltip.configurator.toggleColor") + ": " + (color != null ? color.getName() : EnumColor.BLACK + MekanismUtils.localize("gui.none"))));

		return true;
	}

	@Override
	public boolean onRightClick(EntityPlayer player, int side)
	{
		super.onRightClick(player, side);
		
		player.addChatMessage(new ChatComponentText(EnumColor.DARK_BLUE + "[Mekanism]" + EnumColor.GREY + " " + MekanismUtils.localize("tooltip.configurator.viewColor") + ": " + (color != null ? color.getName() : "None")));
		
		return true;
	}

	@Override
	public EnumColor getColor()
	{
		return color;
	}

	@Override
	public void setColor(EnumColor c)
	{
		color = c;
	}

	@Override
	public EnumColor getRenderColor(boolean post)
	{
		return post ? null : color;
	}
	
	@Override
	public boolean transparencyRender()
	{
		return true;
	}

	@Override
	public boolean canEmitTo(TileEntity tileEntity, ForgeDirection side)
	{
		if(!canConnect(side))
		{
			return false;
		}

		return getConnectionType(side) == ConnectionType.NORMAL || getConnectionType(side) == ConnectionType.PUSH;
	}

	@Override
	public boolean canReceiveFrom(TileEntity tileEntity, ForgeDirection side)
	{
		if(!canConnect(side))
		{
			return false;
		}

		return getConnectionType(side) == ConnectionType.NORMAL;
	}

	@Override
	public void onRemoved()
	{
		super.onRemoved();

		if(!world().isRemote)
		{
			for(TransporterStack stack : transit)
			{
				TransporterUtils.drop(this, stack);
			}
		}
	}

	@Override
	public int getCost()
	{
		return 1;
	}
	
	@Override
	public int getTransmitterNetworkSize()
	{
		return getTransmitterNetwork().getSize();
	}

	@Override
	public int getTransmitterNetworkAcceptorSize()
	{
		return getTransmitterNetwork().getAcceptorSize();
	}

	@Override
	public String getTransmitterNetworkNeeded()
	{
		return getTransmitterNetwork().getNeededInfo();
	}

	@Override
	public String getTransmitterNetworkFlow()
	{
		return getTransmitterNetwork().getFlowInfo();
	}

	@Override
	public int getCapacity()
	{
		return 0;
	}

	@Override
	public InventoryNetwork createNetworkFromSingleTransmitter(IGridTransmitter<InventoryNetwork> transmitter) 
	{
		return new InventoryNetwork(transmitter);
	}

	@Override
	public InventoryNetwork createNetworkByMergingSet(Set<InventoryNetwork> networks)
	{
		return new InventoryNetwork(networks);
	}
}
